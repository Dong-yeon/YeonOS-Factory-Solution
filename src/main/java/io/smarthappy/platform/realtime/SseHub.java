package io.smarthappy.platform.realtime;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import lombok.extern.slf4j.Slf4j;

/**
 * 화면 실시간 신호 — Server-Sent Events 시범 (docs/70 3장).
 * <p>
 * <b>신호만 보내고 데이터는 보내지 않는다</b>: 이벤트 본문은 "바뀌었다"(이름 + 고정 값)뿐이고, 화면은 신호를 받으면 <b>기존 API</b>(@ProgAuth·RLS)를
 * 다시 부른다. 그래서 권한·회사 범위 판단이 한 곳(기존 API)에 남고, 신호 쪽에서 새어 나갈 데이터가 없다.
 * <ul>
 * <li>구독 키 = (회사, 사용자) — 로그인 principal 에서만(프론트가 보낸 값 없음). 다른 회사·다른 사용자의 신호는 받지 않는다</li>
 * <li>연결 수명 {@link #TIMEOUT_MS} — 끝나면 브라우저가 다시 연결하고, 그때 세션·계정·회사 유효성·권한을 다시 확인받는다
 *     (사용 중지·세션 끊김이 최대 이 시간 안에 신호에도 반영)</li>
 * <li>상한: 사용자당 {@link #PER_USER}개(넘으면 가장 오래된 연결을 닫음 — 탭 여러 개), 전체 {@link #MAX_TOTAL}개(넘으면 429 — 폴링으로 돌아감)</li>
 * <li>{@link #HEARTBEAT_SEC}초마다 주석 줄 — 프록시가 조용한 연결을 끊지 않게</li>
 * <li>보내기는 <b>커밋 뒤</b>({@link #publishAfterCommit}) — 롤백된 변경의 신호를 보내지 않는다</li>
 * <li>인스턴스 안에서만(메모리) — 서버 여러 대면 다른 서버에 붙은 화면은 신호를 못 받고 폴링으로만 갱신된다(docs/70 3.4, 시범의 한계)</li>
 * </ul>
 * 비동기 서블릿이라 연결 하나가 요청 스레드를 잡고 있지 않는다(가상 스레드 여부와 무관).
 */
@Slf4j
@Component
public class SseHub implements DisposableBean {

    static final long TIMEOUT_MS = 5 * 60 * 1000L;
    static final int PER_USER = 5;
    static final int MAX_TOTAL = 2000;
    static final int HEARTBEAT_SEC = 25;

    record Key(String compCd, long userId) {
    }

    private final Map<Key, List<SseEmitter>> subs = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public SseHub() {
        heartbeat.scheduleAtFixedRate(this::beat, HEARTBEAT_SEC, HEARTBEAT_SEC, TimeUnit.SECONDS);
    }

    /** 구독 열기 — 호출하는 쪽은 로그인 principal 의 회사·사용자만 넘긴다 */
    public SseEmitter open(String compCd, long userId) {
        return open(compCd, userId, TIMEOUT_MS);
    }

    SseEmitter open(String compCd, long userId, long timeoutMs) {
        if (total.get() >= MAX_TOTAL) {
            throw new BusinessException(HbErrorCode.TOO_MANY_REQUESTS);
        }
        Key key = new Key(compCd, userId);
        SseEmitter e = new SseEmitter(timeoutMs);
        // 추가·빈 목록 정리를 같은 키 잠금(compute) 안에서 — 정리 중인 목록에 붙어 사라지지 않게
        List<SseEmitter> list = subs.compute(key, (k, l) -> {
            List<SseEmitter> v = l == null ? new CopyOnWriteArrayList<>() : l;
            v.add(e);
            return v;
        });
        total.incrementAndGet();
        Runnable remove = () -> remove(key, e);
        e.onCompletion(remove);
        e.onTimeout(() -> {
            remove(key, e);
            e.complete();
        });
        e.onError(t -> remove(key, e));
        while (list.size() > PER_USER) {   // 탭을 많이 열면 오래된 것부터 닫는다
            SseEmitter oldest = list.get(0);
            remove(key, oldest);
            oldest.complete();
        }
        // 첫 줄 — 연결 확인 + 끊겼을 때 다시 붙는 간격
        send(key, e, SseEmitter.event().name("ready").data("1").reconnectTime(5000));
        return e;
    }

    /** 그 회사의 그 사용자에게 신호(즉시) */
    public void publish(String compCd, long userId, String event) {
        Key key = new Key(compCd, userId);
        List<SseEmitter> list = subs.get(key);
        if (list == null) {
            return;
        }
        for (SseEmitter e : list) {
            send(key, e, SseEmitter.event().name(event).data("1"));
        }
    }

    /** 트랜잭션 안이면 커밋 뒤에, 밖이면 바로 — 롤백된 변경의 신호를 보내지 않는다 */
    public void publishAfterCommit(String compCd, long userId, String event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(compCd, userId, event);
                }
            });
        } else {
            publish(compCd, userId, event);
        }
    }

    /** 지금 열린 연결 수(지표·시험) */
    public int size() {
        return total.get();
    }

    int size(String compCd, long userId) {
        List<SseEmitter> list = subs.get(new Key(compCd, userId));
        return list == null ? 0 : list.size();
    }

    private void beat() {
        subs.forEach((key, list) -> list.forEach(e -> send(key, e, SseEmitter.event().comment("hb"))));
    }

    private void send(Key key, SseEmitter e, SseEmitter.SseEventBuilder ev) {
        try {
            e.send(ev);
        } catch (IOException | IllegalStateException ex) {
            // 끊긴 연결(탭 닫힘·프록시 끊김) — 정리만
            remove(key, e);
            try {
                e.complete();
            } catch (RuntimeException ignored) {
                // 이미 끝남
            }
        }
    }

    private void remove(Key key, SseEmitter e) {
        subs.computeIfPresent(key, (k, l) -> {
            if (l.remove(e)) {
                total.decrementAndGet();
            }
            return l.isEmpty() ? null : l;
        });
    }

    @Override
    public void destroy() {
        heartbeat.shutdownNow();
        subs.values().forEach(list -> list.forEach(e -> {
            try {
                e.complete();
            } catch (RuntimeException ignored) {
                // 종료 중
            }
        }));
        subs.clear();
        total.set(0);
    }
}

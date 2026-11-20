package io.smarthappy.platform.pda;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * 현장 단말 APK 배포 (docs/50 2~4장). 운영자가 서버 디스크 {@code happyboot.pda.dir} 에 고정 이름 두 파일을 놓는다 —
 * {@code yeonos-pda.apk} + {@code release.json}. 요청 값으로 경로를 만들지 않는다(F1).
 *
 * - release.json 은 허용 칸만 읽고 하나라도 틀리면 INVALID — 조용히 옛 값을 쓰지 않는다(F2)
 * - 크기·SHA-256 은 파일에서 직접 계산, 크기·수정 시각이 바뀔 때만 다시(F3)
 * - 심볼릭 링크·디렉터리 밖 실제 경로는 거부(D4)
 */
@Slf4j
@Service
public class PdaAppService {

    public static final String PROG = "sys-pda-app";
    static final String APK = "yeonos-pda.apk";
    static final String RELEASE = "release.json";
    static final int MAX_RELEASE_BYTES = 8192;
    static final int MAX_NOTES = 500;
    private static final Pattern SEMVER = Pattern.compile("^(0|[1-9]\\d{0,3})\\.(0|[1-9]\\d{0,3})\\.(0|[1-9]\\d{0,3})$");
    /** 줄바꿈·탭 말고 제어 문자 + 보이지 않는 방향 문자 */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]|[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069]");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 배포 상태: OK = 내려받기 가능 / MISSING = 파일 없음(설정 전) / INVALID = release.json 이 틀림 */
    public enum State {
        OK, MISSING, INVALID
    }

    /** release.json 의 허용 칸 (검사를 통과한 값만) */
    public record Release(String version, String minVersion, LocalDate releasedAt, String notesKo, String notesEn) {
    }

    /** 화면에 보이는 것 — APK 가 없으면 크기·해시 null */
    public record Info(State state, Release release, Long sizeBytes, String sha256) {
    }

    /** 내려받기 — 호출한 쪽이 닫는다. sizeBytes 는 연 파일의 실제 길이(Content-Length) */
    public record Download(Release release, long sizeBytes, String sha256, InputStream content) {
    }

    private record Hashed(long size, long modified, String sha256) {
    }

    private final Path dir;
    private final AtomicReference<Hashed> hashCache = new AtomicReference<>();

    public PdaAppService(@Value("${happyboot.pda.dir:./data/pda}") String dir) {
        this.dir = Path.of(dir).toAbsolutePath().normalize();
    }

    /** /pda/version.json — 로그인 전 공개, 세 칸만(V1). release.json 이 없거나 틀리면 빈 값(껍데기는 확인 없이 연다) */
    public Map<String, Object> publicVersion() {
        Map<String, Object> out = new LinkedHashMap<>();
        Release r;
        try {
            r = readRelease();
        } catch (InvalidRelease e) {
            r = null;
        }
        if (r != null) {
            out.put("version", r.version());
            out.put("minVersion", r.minVersion());
            out.put("releasedAt", r.releasedAt().toString());
        }
        return out;
    }

    /** 다운로드 화면 */
    public Info info() {
        Release r;
        try {
            r = readRelease();
        } catch (InvalidRelease e) {
            return new Info(State.INVALID, null, null, null);
        }
        Path apk = apkFile();
        if (r == null || apk == null) {
            return new Info(State.MISSING, r, null, null);
        }
        try {
            Hashed h = hash(apk);
            return new Info(State.OK, r, h.size(), h.sha256());
        } catch (IOException e) {
            log.warn("PDA APK 읽기 실패 ({})", e.getClass().getSimpleName());
            return new Info(State.MISSING, r, null, null);
        }
    }

    /** 내려받기 — OK 가 아니면 409 PDA_APP_NOT_READY */
    public Download download() {
        Info info = info();
        Path apk = info.state() == State.OK ? apkFile() : null;
        if (apk == null) {
            throw new BusinessException(HbErrorCode.PDA_APP_NOT_READY);
        }
        try {
            // 길이는 연 파일에서 — 바꿔치기(rename) 순간에도 Content-Length 가 실제 보내는 바이트와 같게
            SeekableByteChannel ch = Files.newByteChannel(apk, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            return new Download(info.release(), ch.size(), info.sha256(), Channels.newInputStream(ch));
        } catch (IOException e) {
            throw new BusinessException(HbErrorCode.PDA_APP_NOT_READY);
        }
    }

    /** semver 비교 (숫자 셋) — a < b 면 음수 */
    static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** release.json 이 틀림 — 화면은 INVALID, 공개 버전은 빈 값 */
    static final class InvalidRelease extends RuntimeException {
        InvalidRelease(String why) {
            super(why, null, false, false);
        }
    }

    /** 없으면 null, 틀리면 InvalidRelease (publicVersion 에서는 빈 값으로) */
    private Release readRelease() {
        Path f = safeFile(RELEASE);
        if (f == null) {
            return null;
        }
        try {
            if (Files.size(f) > MAX_RELEASE_BYTES) {
                throw new InvalidRelease("too large");
            }
            Map<?, ?> raw = JSON.readValue(Files.readString(f), Map.class);
            String version = semver(raw.get("version"));
            String min = semver(raw.get("minVersion"));
            if (compare(min, version) > 0) {
                throw new InvalidRelease("minVersion > version");
            }
            if (!(raw.get("releasedAt") instanceof String d)) {
                throw new InvalidRelease("releasedAt");
            }
            LocalDate releasedAt = LocalDate.parse(d);
            return new Release(version, min, releasedAt, notes(raw.get("notesKo")), notes(raw.get("notesEn")));
        } catch (InvalidRelease e) {
            log.warn("PDA release.json 오류 — {}", e.getMessage());
            throw e;
        } catch (IOException | RuntimeException e) {   // 날짜 형식(DateTimeParseException)·JSON 오류 포함
            log.warn("PDA release.json 읽기 실패 ({})", e.getClass().getSimpleName());
            throw new InvalidRelease(e.getClass().getSimpleName());
        }
    }

    private static String semver(Object v) {
        if (v instanceof String s && SEMVER.matcher(s).matches()) {
            return s;
        }
        throw new InvalidRelease("semver");
    }

    private static String notes(Object v) {
        if (v == null) {
            return null;
        }
        if (!(v instanceof String s)) {
            throw new InvalidRelease("notes");
        }
        String clean = CONTROL.matcher(s).replaceAll("").strip();
        if (clean.length() > MAX_NOTES) {
            throw new InvalidRelease("notes too long");
        }
        return clean.isEmpty() ? null : clean;
    }

    private Path apkFile() {
        return safeFile(APK);
    }

    /**
     * 설정 디렉터리 바로 아래의 일반 파일만 — 심볼릭 링크(파일·상위 경로 어디든)로 밖을 가리키면 없음으로 본다.
     * 디렉터리가 링크여도 실제 경로를 기준으로 그 안에 있어야 한다
     */
    private Path safeFile(String name) {
        Path f = dir.resolve(name);
        try {
            BasicFileAttributes a = Files.readAttributes(f, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!a.isRegularFile()) {
                log.warn("PDA 배포 파일이 일반 파일이 아님(링크·디렉터리) — {}", name);
                return null;
            }
            Path realDir = dir.toRealPath();
            Path real = f.toRealPath();
            if (!real.getParent().equals(realDir)) {
                log.warn("PDA 배포 파일이 설정 디렉터리 밖 — {}", name);
                return null;
            }
            return real;
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            log.warn("PDA 배포 파일 확인 실패 ({}) — {}", e.getClass().getSimpleName(), name);
            return null;
        }
    }

    private Hashed hash(Path apk) throws IOException {
        BasicFileAttributes a = Files.readAttributes(apk, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        long size = a.size();
        long modified = a.lastModifiedTime().toMillis();
        Hashed cached = hashCache.get();
        if (cached != null && cached.size() == size && cached.modified() == modified) {
            return cached;
        }
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        long read = 0;
        try (InputStream in = Files.newInputStream(apk, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0;) {
                md.update(buf, 0, n);
                read += n;
            }
        }
        Hashed h = new Hashed(read, modified, HexFormat.of().formatHex(md.digest()));
        hashCache.set(h);
        return h;
    }
}

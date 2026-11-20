package io.smarthappy.platform.file;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.BusinessException.Detail;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.log.AccessEvent;
import io.smarthappy.platform.log.AccessLogService;
import io.smarthappy.platform.log.AccessLogService.Result;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;

/**
 * 첨부 (01 6단계). 권한은 대상 화면(target_type = prog_cd) 기준: 목록·내려받기 = SEARCH, 올리기·삭제 = SAVE.
 *
 * 올리기 검증 순서: 이름 정리 → 확장자(항상 거부 목록 + 회사 허용 목록) → 크기(선언값과 실제 바이트 둘 다) →
 * 앞부분 시그니처가 확장자와 맞는지 → SHA-256 → 저장소에 옮긴 뒤 메타 기록(실패하면 파일 되돌림).
 * 다른 회사 첨부는 RLS 로 보이지 않으므로 "없음"(404)이다.
 * 삭제는 use_yn = 'N' (변경 감사에 남고, 실제 파일은 보존 클래스 FILE 에 따라 남긴다).
 */
@Slf4j
@Service
public class FileService {

    static final Pattern TARGET_TYPE = Pattern.compile("^[a-z0-9][a-z0-9-]{1,49}$");
    static final Pattern TARGET_ID = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");
    private static final Pattern EXT = Pattern.compile("^[a-z0-9]{1,10}$");
    /** 제어문자 + 양방향 제어문자(“invoice‮fdp.exe” 같은 표시 속임) + 0폭 문자 */
    private static final Pattern BAD_NAME_CHARS = Pattern.compile("[\\p{Cntrl}\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]");
    private static final int MAX_NAME = 255;
    private static final int HEAD_BYTES = 64;

    private static final String COLUMNS = """
            file_id, target_type, target_id, orig_name, ext, content_type, size_bytes, sha256, storage_key,
            created_at, created_by""";
    private static final RowMapper<FileInfo> ROW = (rs, i) -> new FileInfo(rs.getLong("file_id"),
            rs.getString("target_type"), rs.getString("target_id"), rs.getString("orig_name"), rs.getString("ext"),
            rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sha256"), rs.getString("storage_key"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getString("created_by"));

    private final FileStorage storage;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final PermissionService permissions;
    private final PolicyService policies;
    private final AccessLogService accessLog;
    private final ObjectProvider<FileReadGrant> readGrants;
    private final ObjectProvider<FileLockRule> lockRules;

    public FileService(FileStorage storage, JdbcTemplate jdbc, PlatformTransactionManager tm,
            PermissionService permissions, PolicyService policies, AccessLogService accessLog,
            ObjectProvider<FileReadGrant> readGrants, ObjectProvider<FileLockRule> lockRules) {
        this.storage = storage;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.readTx = new TransactionTemplate(tm);
        this.readTx.setReadOnly(true);
        this.permissions = permissions;
        this.policies = policies;
        this.accessLog = accessLog;
        this.readGrants = readGrants;
        this.lockRules = lockRules;
    }

    /** storageKey 는 화면에 내보내지 않는다({@link FileView}) */
    public record FileInfo(long fileId, String targetType, String targetId, String origName, String ext,
            String contentType, long sizeBytes, String sha256, String storageKey, OffsetDateTime createdAt,
            String createdBy) {
        public FileView view() {
            return new FileView(fileId, origName, ext, sizeBytes, sha256, createdAt, createdBy);
        }
    }

    public record FileView(long fileId, String name, String ext, long size, String sha256, OffsetDateTime createdAt,
            String createdBy) {
    }

    public record Download(FileInfo info, InputStream content) {
    }

    public FileInfo upload(String targetType, String targetId, MultipartFile file) {
        requireTarget(targetType, targetId);
        LoginUser user = requirePermission(targetType, Action.SAVE);
        requireUnlocked(targetType, targetId);
        if (file == null) {
            throw reject(targetType, null, "empty", "file.reject.empty");
        }
        int maxMb = readTx.execute(s -> policies.intValue(Policies.FILE_MAX_MB));
        Set<String> allowed = allowedExt();
        Received r;
        try (InputStream in = file.getInputStream()) {
            r = receive(targetType, null, file.getOriginalFilename(), file.getSize(), in, allowed, maxMb);
        } catch (IOException e) {
            throw new UncheckedIOException("첨부 저장 실패", e);
        }
        try {
            String compCd = TenantContext.requireCompCd();
            String key = newKey(compCd);
            storage.store(key, r.temp());
            FileInfo saved;
            try {
                saved = tx.execute(s -> {
                    // 파일을 받는 사이 대상이 잠겼을 수 있다(배포 등) — 메타 기록 트랜잭션 안에서 한 번 더
                    if (isLocked(targetType, targetId)) {
                        throw new BusinessException(HbErrorCode.FILE_LOCKED);
                    }
                    return insert(compCd, targetType, targetId, r, key, user.loginId());
                });
            } catch (RuntimeException e) {
                storage.deleteQuietly(key);
                throw e;
            }
            accessLog.recordCurrent(AccessEvent.FILE_UPLOAD, targetType, Action.SAVE.name(), Result.OK,
                    "fileId=" + saved.fileId());
            return saved;
        } catch (IOException e) {
            throw new UncheckedIOException("첨부 저장 실패", e);
        } finally {
            deleteTemp(r.temp());
        }
    }

    /**
     * 업무 계약이 파일을 대신 붙인다 (C25 Q1, docs/33 11.17 — 예: POP 고장 신고 사진·설비 보전 기록).
     * **업무 트랜잭션 안에서만**(MANDATORY) · **권한 검사 없음** — 부르는 업무 계약이 대상 권한·소유를 이미 확인했다.
     * 이름·확장자(거부 목록 + 회사 허용 목록)·크기(선언·실제)·시그니처·잠금·SHA-256·접근 로그는 upload 와 같은 코드.
     * actor = 행위자 글자(created_by, 50자 — 로그인 사용자가 없는 단말 경로도 있다), content 는 부른 쪽이 닫는다. 메타는 업무 트랜잭션에 같이 들어가고,
     * 그 트랜잭션이 커밋되지 않으면 저장소에 옮긴 실제 파일을 지운다(copyAll 과 같은 afterCompletion). 올림 로그는 커밋 뒤에만
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public FileInfo attachAs(String targetType, String targetId, String fileName, long declaredSize, InputStream content,
            String actor) {
        return attachAs(targetType, targetId, fileName, declaredSize, content, actor, AttachOptions.NONE).file();
    }

    /**
     * 업무 계약이 좁히는 조건 (docs/33 11.17 — POP 사진: 이미지만·작은 한도·재전송). **넓히지 못한다**:
     * onlyExt = 회사 허용 목록과의 교집합(null = 회사 목록 그대로), maxMb = FILE_MAX_MB 와 작은 쪽(null = 정책 그대로, 1 이상),
     * reuseSameContent = 같은 대상의 사용 중 파일 중 SHA-256 이 같은 것이 있으면 새로 만들지 않고 그것을 돌려준다(단말 재전송)
     */
    public record AttachOptions(Set<String> onlyExt, Integer maxMb, boolean reuseSameContent) {
        public static final AttachOptions NONE = new AttachOptions(null, null, false);
    }

    /**
     * 좁힌 조건의 실제 한도 — attachAs 가 판정에 쓰는 값 그대로(같은 계산, docs/25 4.44: POP 이 고르기 전에 한도를 알린다).
     * exts = 회사 허용 목록 ∩ onlyExt − 항상 거부 목록(소문자·점 없이·정렬), maxMb = FILE_MAX_MB 와 maxMb 중 작은 쪽, maxBytes = maxMb × 1MiB(이 바이트까지 통과)
     */
    public record AttachLimits(List<String> exts, int maxMb) {
        public long maxBytes() {
            return bytesOf(maxMb);
        }
    }

    /** 업무 계약이 한도를 미리 알릴 때 — 부른 쪽 트랜잭션 안(SUPPORTS·읽기 전용, 정책은 회사 범위). 권한 검사 없음(부르는 계약 몫) */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public AttachLimits limitsOf(AttachOptions options) {
        return limits(options == null ? AttachOptions.NONE : options);
    }

    private AttachLimits limits(AttachOptions opt) {
        if (opt.maxMb() != null && opt.maxMb() < 1) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        Set<String> allowed = allowedExtInTx();
        if (opt.onlyExt() != null) {
            Set<String> only = opt.onlyExt().stream().filter(Objects::nonNull)
                    .map(e -> e.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            allowed = allowed.stream().filter(only::contains).collect(Collectors.toUnmodifiableSet());
        }
        int maxMb = policies.intValue(Policies.FILE_MAX_MB);
        if (opt.maxMb() != null) {
            maxMb = Math.min(maxMb, opt.maxMb());
        }
        return new AttachLimits(allowed.stream().filter(e -> !FileTypeRules.ALWAYS_BLOCKED.contains(e)).sorted().toList(), maxMb);
    }

    private static long bytesOf(int maxMb) {
        return maxMb * 1024L * 1024L;
    }

    /** duplicate = 같은 내용이 이미 있어 그 파일을 돌려줬다(새 행·새 실제 파일 없음) */
    public record Attached(FileInfo file, boolean duplicate) {
    }

    /** 위 attachAs + 좁히는 조건. 검사·트랜잭션·로그 규칙은 같다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Attached attachAs(String targetType, String targetId, String fileName, long declaredSize, InputStream content,
            String actor, AttachOptions options) {
        requireTarget(targetType, targetId);
        AttachOptions opt = options == null ? AttachOptions.NONE : options;
        AttachLimits limits = limits(opt);
        String who = actor == null ? null : actor.strip();
        if (who == null || who.isEmpty() || who.length() > 50 || BAD_NAME_CHARS.matcher(who).find()) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (isLocked(targetType, targetId)) {
            throw new BusinessException(HbErrorCode.FILE_LOCKED);
        }
        if (content == null) {
            throw reject(targetType, who, "empty", "file.reject.empty");
        }
        String compCd = TenantContext.requireCompCd();
        Received r = receive(targetType, who, fileName, declaredSize, content, Set.copyOf(limits.exts()), limits.maxMb());
        try {
            if (opt.reuseSameContent()) {
                // 같은 대상의 동시 재전송이 둘 다 새 행을 만들지 않게 대상 단위로 한 줄 세운다(트랜잭션 끝까지)
                jdbc.queryForObject("select 1 from (select pg_advisory_xact_lock(hashtextextended(?, 0))) x", Integer.class,
                        "sys_file:" + compCd + ":" + targetType + ":" + targetId);
                List<FileInfo> same = jdbc.query("select " + COLUMNS + """
                         from sys_file
                        where target_type = ? and target_id = ? and sha256 = ? and use_yn = 'Y'
                        order by file_id limit 1""", ROW, targetType, targetId, r.sha256());
                if (!same.isEmpty()) {
                    return new Attached(same.get(0), true);
                }
            }
            String key = newKey(compCd);
            storage.store(key, r.temp());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        storage.deleteQuietly(key);
                    }
                }
            });
            FileInfo saved = insert(compCd, targetType, targetId, r, key, who);
            Long userId = CurrentUser.get().map(LoginUser::userId).orElse(null);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    accessLog.record(AccessEvent.FILE_UPLOAD, compCd, userId, who, targetType, Action.SAVE.name(), Result.OK,
                            "fileId=" + saved.fileId() + " attachAs");
                }
            });
            return new Attached(saved, false);
        } catch (IOException e) {
            throw new UncheckedIOException("첨부 저장 실패", e);
        } finally {
            deleteTemp(r.temp());
        }
    }

    /**
     * 한 대상의 사용 중 첨부 중 행위자 글자가 actorPrefix 로 시작하는 수 — 업무 계약의 개수 한도용(예: POP 사진 = "t" 로 붙인 것만).
     * MANDATORY · 권한 검사 없음(부르는 계약 몫). actorPrefix 빈 값은 거부(전체 세기는 이 메서드의 일이 아니다). LIKE 와일드카드는 글자 그대로
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int countAttached(String targetType, String targetId, String actorPrefix) {
        requireTarget(targetType, targetId);
        if (actorPrefix == null || actorPrefix.isBlank() || actorPrefix.length() > 50) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String like = actorPrefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        Integer n = jdbc.queryForObject("""
                select count(*) from sys_file
                 where target_type = ? and target_id = ? and use_yn = 'Y' and created_by like ? escape '\\'""",
                Integer.class, targetType, targetId, like);
        return n == null ? 0 : n;
    }

    /** 보낼 수 있는 첨부 판정 결과 — files = 요청 순서대로 통과한 것, notFound = 그 대상의 사용 중 파일이 아님, blocked = 지금 규칙상 확장자 불가 */
    public record Sendable(List<FileInfo> files, List<Long> notFound, List<Long> blocked) {
        public boolean ok() {
            return notFound.isEmpty() && blocked.isEmpty();
        }
    }

    /**
     * 업무 계약이 파일을 밖으로 보낼 때(외부 메일 첨부, docs/36 18.6 A1·A2) — 이 대상의 이 파일들을 보낼 수 있나.
     * **업무 트랜잭션 안에서만**(MANDATORY) · **권한 검사 없음**(부르는 계약이 대상 권한·소유를 확인했다).
     * 지금 회사(RLS)의 그 targetType/targetId 에 붙은 사용 중 파일만 통과(다른 회사·다른 대상·지운 파일 = notFound).
     * 확장자는 올릴 때와 같은 규칙(항상 거부 목록 + **지금** 회사 허용 목록)을 다시 쓴다 — 그 사이 목록이 좁아졌으면 blocked.
     * 시그니처·SHA-256 은 올릴 때 검사해 행에 남은 값을 쓴다. 중복 id 는 한 번만
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Sendable sendable(String targetType, String targetId, List<Long> fileIds) {
        requireTarget(targetType, targetId);
        List<Long> ids = fileIds == null ? List.of() : fileIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return new Sendable(List.of(), List.of(), List.of());
        }
        String compCd = TenantContext.requireCompCd();
        List<FileInfo> rows = jdbc.query(con -> {
            var ps = con.prepareStatement("select " + COLUMNS + """
                     from sys_file
                    where comp_cd = ? and target_type = ? and target_id = ? and file_id = any(?) and use_yn = 'Y'""");
            ps.setString(1, compCd);
            ps.setString(2, targetType);
            ps.setString(3, targetId);
            ps.setArray(4, con.createArrayOf("bigint", ids.toArray()));
            return ps;
        }, ROW);
        java.util.Map<Long, FileInfo> byId = rows.stream().collect(Collectors.toMap(FileInfo::fileId, f -> f));
        Set<String> allowed = allowedExtInTx();
        List<FileInfo> ok = new ArrayList<>();
        List<Long> notFound = new ArrayList<>();
        List<Long> blocked = new ArrayList<>();
        for (Long id : ids) {
            FileInfo f = byId.get(id);
            if (f == null) {
                notFound.add(id);
            } else if (FileTypeRules.ALWAYS_BLOCKED.contains(f.ext()) || !allowed.contains(f.ext())) {
                blocked.add(id);
            } else {
                ok.add(f);
            }
        }
        return new Sendable(List.copyOf(ok), List.copyOf(notFound), List.copyOf(blocked));
    }

    /** 받은 파일(검사 통과) — 임시 파일은 부른 쪽이 지운다 */
    private record Received(Path temp, String name, String ext, String contentType, long size, String sha256) {
    }

    /**
     * upload·attachAs 공통 검사: 이름 정리 → 확장자(항상 거부 목록 + 회사 허용 목록) → 크기(선언값과 실제 바이트 둘 다) →
     * 앞부분 시그니처가 확장자와 맞는지 → SHA-256. 거부되면 임시 파일을 지우고 FILE_REJECTED. actor = 거부 로그의 행위자(null 이면 로그인 사용자)
     */
    private Received receive(String targetType, String actor, String rawName, long declaredSize, InputStream in,
            Set<String> allowed, int maxMb) {
        String name = cleanName(rawName);
        if (name == null) {
            throw reject(targetType, actor, "name", "file.reject.name");
        }
        String ext = FileTypeRules.extension(name);
        if (!EXT.matcher(ext).matches() || FileTypeRules.ALWAYS_BLOCKED.contains(ext) || !allowed.contains(ext)) {
            throw reject(targetType, actor, "ext", "file.reject.ext");
        }
        long maxBytes = bytesOf(maxMb);
        if (declaredSize > maxBytes) {
            throw reject(targetType, actor, "size", "file.reject.size", maxMb);
        }
        Path temp = null;
        boolean ok = false;
        try {
            temp = storage.newTempFile();
            byte[] head = new byte[HEAD_BYTES];
            int headLen = 0;
            long total = 0;
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (OutputStream out = Files.newOutputStream(temp)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) {
                    if (headLen < HEAD_BYTES) {
                        int n = Math.min(r, HEAD_BYTES - headLen);
                        System.arraycopy(buf, 0, head, headLen, n);
                        headLen += n;
                    }
                    total += r;
                    if (total > maxBytes) {
                        // 선언 크기를 속인 경우: 실제 바이트로 다시 막는다
                        throw reject(targetType, actor, "size", "file.reject.size", maxMb);
                    }
                    sha.update(buf, 0, r);
                    out.write(buf, 0, r);
                }
            }
            if (total == 0) {
                throw reject(targetType, actor, "empty", "file.reject.empty");
            }
            if (!FileTypeRules.contentMatches(ext, head, headLen)) {
                throw reject(targetType, actor, "content:" + ext, "file.reject.content");
            }
            ok = true;
            return new Received(temp, name, ext, FileTypeRules.contentType(ext), total,
                    HexFormat.of().formatHex(sha.digest()));
        } catch (IOException e) {
            throw new UncheckedIOException("첨부 저장 실패", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            if (!ok) {
                deleteTemp(temp);
            }
        }
    }

    private static String newKey(String compCd) {
        YearMonth ym = YearMonth.now(ZoneOffset.UTC);
        return String.format(Locale.ROOT, "%s/%04d/%02d/%s", compCd, ym.getYear(), ym.getMonthValue(), UUID.randomUUID());
    }

    private FileInfo insert(String compCd, String targetType, String targetId, Received r, String key, String by) {
        return jdbc.queryForObject("""
                insert into sys_file (comp_cd, target_type, target_id, orig_name, ext, content_type, size_bytes,
                                      sha256, storage_key, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning\s""" + COLUMNS, ROW, compCd, targetType, targetId, r.name(), r.ext(), r.contentType(), r.size(),
                r.sha256(), key, by, by);
    }

    public List<FileInfo> list(String targetType, String targetId) {
        requireTarget(targetType, targetId);
        requireRead(targetType, targetId);
        return readTx.execute(s -> jdbc.query("select " + COLUMNS + """
                 from sys_file
                where target_type = ? and target_id = ? and use_yn = 'Y'
                order by file_id""", ROW, targetType, targetId));
    }

    /**
     * 한 대상의 사용 중 첨부를 다른 대상으로 복사한다 — 실제 파일도 새 저장 키로(저장 키는 행마다 유일). 리비전 문서의 "새 리비전 = 첨부까지 복사"
     * (docs/29 Q3, 26 9번)용. **업무 트랜잭션 안에서만**(MANDATORY) — 그 트랜잭션이 커밋되지 않으면 복사한 실제 파일을 지운다.
     * 권한은 부르는 업무 API 가 이미 확인했다(대상 화면 SAVE). 같은 회사 범위 안에서만 보인다(RLS)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int copyAll(String targetType, String fromTargetId, String toTargetId) {
        requireTarget(targetType, fromTargetId);
        requireTarget(targetType, toTargetId);
        LoginUser user = CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
        String compCd = TenantContext.requireCompCd();
        List<FileInfo> src = jdbc.query("select " + COLUMNS + """
                 from sys_file
                where target_type = ? and target_id = ? and use_yn = 'Y'
                order by file_id""", ROW, targetType, fromTargetId);
        List<String> copied = new ArrayList<>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    copied.forEach(storage::deleteQuietly);
                }
            }
        });
        for (FileInfo f : src) {
            Path temp = null;
            try {
                temp = storage.newTempFile();
                try (InputStream in = storage.open(f.storageKey())) {
                    Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                }
                YearMonth ym = YearMonth.now(ZoneOffset.UTC);
                String key = String.format(Locale.ROOT, "%s/%04d/%02d/%s", compCd, ym.getYear(), ym.getMonthValue(),
                        UUID.randomUUID());
                storage.store(key, temp);
                copied.add(key);
                jdbc.update("""
                        insert into sys_file (comp_cd, target_type, target_id, orig_name, ext, content_type, size_bytes,
                                              sha256, storage_key, created_by, updated_by)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", compCd, targetType, toTargetId, f.origName(), f.ext(),
                        f.contentType(), f.sizeBytes(), f.sha256(), key, user.loginId(), user.loginId());
            } catch (IOException e) {
                throw new UncheckedIOException("첨부 복사 실패 fileId=" + f.fileId(), e);
            } finally {
                deleteTemp(temp);
            }
        }
        return src.size();
    }

    public Download download(long fileId) {
        FileInfo f = find(fileId);
        requireRead(f.targetType(), f.targetId());
        try {
            InputStream in = storage.open(f.storageKey());
            accessLog.recordCurrent(AccessEvent.FILE_DOWNLOAD, f.targetType(), Action.SEARCH.name(), Result.OK,
                    "fileId=" + fileId);
            return new Download(f, in);
        } catch (IOException e) {
            log.error("첨부 파일 없음(메타는 있음) fileId={}", fileId, e);
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
    }

    public void delete(long fileId) {
        FileInfo f = find(fileId);
        LoginUser user = requirePermission(f.targetType(), Action.SAVE);
        requireUnlocked(f.targetType(), f.targetId());
        Integer n = tx.execute(s -> jdbc.update("""
                update sys_file set use_yn = 'N', row_ver = row_ver + 1, updated_at = now(), updated_by = ?
                 where file_id = ? and use_yn = 'Y'""", user.loginId(), fileId));
        if (n == null || n == 0) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        accessLog.recordCurrent(AccessEvent.FILE_DELETE, f.targetType(), Action.SAVE.name(), Result.OK,
                "fileId=" + fileId);
    }

    private FileInfo find(long fileId) {
        List<FileInfo> rows = readTx.execute(s -> jdbc.query(
                "select " + COLUMNS + " from sys_file where file_id = ? and use_yn = 'Y'", ROW, fileId));
        if (rows == null || rows.isEmpty()) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    private Set<String> allowedExt() {
        return parseExt(readTx.execute(s -> policies.value(Policies.FILE_ALLOWED_EXT)));
    }

    /** 이미 트랜잭션 안(attachAs) — 읽기 전용 트랜잭션을 따로 열지 않는다 */
    private Set<String> allowedExtInTx() {
        return parseExt(policies.value(Policies.FILE_ALLOWED_EXT));
    }

    private static Set<String> parseExt(String raw) {
        return raw == null ? Set.of() : Arrays.stream(raw.split(","))
                .map(v -> v.trim().toLowerCase(Locale.ROOT))
                .filter(v -> EXT.matcher(v).matches())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static void requireTarget(String targetType, String targetId) {
        if (targetType == null || !TARGET_TYPE.matcher(targetType).matches()
                || targetId == null || !TARGET_ID.matcher(targetId).matches()) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
    }

    /** 읽기 = 대상 화면 SEARCH 또는 읽기 허락 규칙(FileReadGrant — 게시 중 공지 첨부 등) */
    private void requireRead(String targetType, String targetId) {
        CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
        if (permissions.actions(targetType).contains(Action.SEARCH)) {
            return;
        }
        Boolean granted = readTx.execute(s -> readGrants.orderedStream().anyMatch(g -> g.allows(targetType, targetId)));
        if (!Boolean.TRUE.equals(granted)) {
            requirePermission(targetType, Action.SEARCH);
        }
    }

    /** 잠금 규칙(FileLockRule) 중 하나라도 잠겼다고 하면 409 */
    private void requireUnlocked(String targetType, String targetId) {
        if (Boolean.TRUE.equals(readTx.execute(s -> isLocked(targetType, targetId)))) {
            throw new BusinessException(HbErrorCode.FILE_LOCKED);
        }
    }

    private boolean isLocked(String targetType, String targetId) {
        return lockRules.orderedStream().anyMatch(r -> r.locked(targetType, targetId));
    }

    /** 대상 화면 권한. 없는 화면 코드도 권한이 없으므로 403 */
    private LoginUser requirePermission(String targetType, Action action) {
        LoginUser user = CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
        if (!permissions.actions(targetType).contains(action)) {
            accessLog.recordCurrent(AccessEvent.PERM_DENIED, targetType, action.name(), Result.DENIED, "file");
            throw new BusinessException(HbErrorCode.NO_PERMISSION);
        }
        return user;
    }

    /** actor = 업무 계약 경로(attachAs)의 행위자 — null 이면 로그인 사용자 기준. 거부 로그는 따로 커밋된다(업무가 롤백돼도 남음) */
    private BusinessException reject(String targetType, String actor, String reason, String messageKey, Object... args) {
        log.info("첨부 거부 prog={} reason={}", targetType, reason);
        if (actor == null) {
            accessLog.recordCurrent(AccessEvent.FILE_REJECTED, targetType, Action.SAVE.name(), Result.DENIED, reason);
        } else {
            accessLog.record(AccessEvent.FILE_REJECTED, TenantContext.requireCompCd(),
                    CurrentUser.get().map(LoginUser::userId).orElse(null), actor, targetType, Action.SAVE.name(),
                    Result.DENIED, reason + " attachAs");
        }
        return new BusinessException(HbErrorCode.FILE_REJECTED, List.of(new Detail(messageKey, args)));
    }

    /**
     * 원본 파일명 정리: 경로 부분 제거, NFC, 제어·양방향·0폭 문자 제거, 끝의 점·공백 제거(윈도우 "a.exe." 우회 방지),
     * 길이 제한(확장자 유지). 남는 이름이 없으면 null
     */
    static String cleanName(String raw) {
        if (raw == null) {
            return null;
        }
        String n = Normalizer.normalize(raw, Normalizer.Form.NFC);
        n = n.substring(Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\')) + 1);
        n = BAD_NAME_CHARS.matcher(n).replaceAll("").strip();
        n = n.replaceAll("[.\\s]+$", "");
        if (n.isEmpty() || n.startsWith(".")) {
            return null;
        }
        if (n.length() > MAX_NAME) {
            String ext = FileTypeRules.extension(n);
            int keep = MAX_NAME - (ext.isEmpty() ? 0 : ext.length() + 1);
            n = n.substring(0, keep) + (ext.isEmpty() ? "" : "." + ext);
        }
        return n;
    }

    private static void deleteTemp(Path temp) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            log.warn("임시 파일 삭제 실패 {}", temp.getFileName());
        }
    }
}

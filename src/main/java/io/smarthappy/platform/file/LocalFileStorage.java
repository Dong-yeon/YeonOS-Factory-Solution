package io.smarthappy.platform.file;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.config.HappyBootProperties;
import lombok.extern.slf4j.Slf4j;

/** 로컬 디스크 저장소. 키 형식을 다시 확인하고, 해석한 경로가 루트 밖이면 거부한다(경로 조작 방지 이중 확인) */
@Slf4j
@Component
public class LocalFileStorage implements FileStorage {

    /** sys_file.ck_sys_file_key 와 같은 규칙 */
    static final Pattern KEY = Pattern.compile("^[A-Z0-9_-]+/[0-9]{4}/[0-9]{2}/[0-9a-f-]{36}$");

    private final Path root;
    private final Path tmp;

    public LocalFileStorage(HappyBootProperties props) throws IOException {
        this.root = Path.of(props.files().root()).toAbsolutePath().normalize();
        this.tmp = root.resolve(".tmp");
        Files.createDirectories(tmp);
    }

    @Override
    public Path newTempFile() throws IOException {
        return Files.createTempFile(tmp, "up-", ".part");
    }

    @Override
    public void store(String key, Path tempFile) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    @Override
    public void deleteQuietly(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException | RuntimeException e) {
            log.error("첨부 파일 되돌리기 실패 key={}", key, e);
        }
    }

    Path resolve(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("잘못된 저장 키");
        }
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("저장 루트 밖 경로");
        }
        return p;
    }
}

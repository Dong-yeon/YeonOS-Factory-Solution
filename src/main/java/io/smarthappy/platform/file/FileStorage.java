package io.smarthappy.platform.file;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * 첨부 실제 파일 저장소. 키 = {comp_cd}/{yyyy}/{MM}/{uuid} (원본 파일명을 경로에 쓰지 않는다).
 * 구현: 로컬 디스크({@link LocalFileStorage}). S3 호환 저장소(SaaS)는 이 인터페이스로 추가한다.
 */
public interface FileStorage {

    /** 업로드 중 내용을 받을 임시 파일 (저장소와 같은 디스크 — 옮기기가 원자적이도록) */
    Path newTempFile() throws IOException;

    /** 임시 파일을 키 위치로 옮긴다. 이미 있으면 실패 */
    void store(String key, Path tempFile) throws IOException;

    InputStream open(String key) throws IOException;

    /** 저장 직후 메타 기록이 실패했을 때 되돌리기용 */
    void deleteQuietly(String key);
}

package io.smarthappy.platform.file;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 첨부 형식 판정: 확장자(회사 정책 허용 목록) + 파일 앞부분 시그니처 + 서버가 정하는 Content-Type.
 * 클라이언트가 보낸 Content-Type 은 쓰지 않는다. 실행·스크립트·브라우저가 해석하는 형식은 정책에 넣어도 받지 않는다.
 */
final class FileTypeRules {

    /** 정책과 관계없이 항상 거부 */
    static final Set<String> ALWAYS_BLOCKED = Set.of("exe", "dll", "com", "scr", "msi", "msp", "bat", "cmd", "ps1",
            "psm1", "vbs", "vbe", "js", "mjs", "jse", "wsf", "wsh", "hta", "lnk", "jar", "class", "sh", "bash", "py",
            "pl", "php", "jsp", "jspx", "asp", "aspx", "cgi", "html", "htm", "xhtml", "shtml", "svg", "svgz", "xml",
            "xsl", "swf", "reg", "inf", "cpl", "msc", "iso", "img", "vhd", "vhdx", "apk", "dmg", "app");

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("tif", "image/tiff"),
            Map.entry("tiff", "image/tiff"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("csv", "text/csv"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("doc", "application/msword"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("hwp", "application/x-hwp"),
            Map.entry("hwpx", "application/hwp+zip"),
            Map.entry("txt", "text/plain"),
            Map.entry("zip", "application/zip"));

    private static final byte[] PDF = ascii("%PDF-");
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF87 = ascii("GIF87a");
    private static final byte[] GIF89 = ascii("GIF89a");
    private static final byte[] RIFF = ascii("RIFF");
    private static final byte[] WEBP = ascii("WEBP");
    private static final byte[] BMP = ascii("BM");
    private static final byte[] TIF_LE = {0x49, 0x49, 0x2A, 0x00};
    private static final byte[] TIF_BE = {0x4D, 0x4D, 0x00, 0x2A};
    private static final byte[] ZIP = {0x50, 0x4B, 0x03, 0x04};
    private static final byte[] ZIP_EMPTY = {0x50, 0x4B, 0x05, 0x06};
    private static final byte[] OLE = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A,
            (byte) 0xE1};
    private static final byte[] DWG = ascii("AC10");
    private static final byte[] MZ = ascii("MZ");
    private static final byte[] ELF = {0x7F, 0x45, 0x4C, 0x46};
    private static final byte[] SHEBANG = ascii("#!");
    private static final byte[] MACHO_FAT = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};

    private static final Set<String> TEXT_TYPES = Set.of("txt", "csv", "dxf", "step", "stp", "igs", "iges");

    private FileTypeRules() {
    }

    static String contentType(String ext) {
        return CONTENT_TYPES.getOrDefault(ext, "application/octet-stream");
    }

    /** 파일명에서 확장자(소문자). 없으면 빈 문자열 */
    static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 앞부분(head)이 확장자와 맞는지. 규칙이 없는 확장자(회사가 정책으로 추가)는 실행 파일 시그니처만 거부 */
    static boolean contentMatches(String ext, byte[] head, int len) {
        if (startsWith(head, len, MZ) || startsWith(head, len, ELF) || startsWith(head, len, MACHO_FAT)) {
            return false;
        }
        return switch (ext) {
            case "pdf" -> startsWith(head, len, PDF);
            case "png" -> startsWith(head, len, PNG);
            case "jpg", "jpeg" -> startsWith(head, len, JPG);
            case "gif" -> startsWith(head, len, GIF87) || startsWith(head, len, GIF89);
            case "webp" -> startsWith(head, len, RIFF) && len >= 12 && regionEquals(head, 8, WEBP);
            case "bmp" -> startsWith(head, len, BMP);
            case "tif", "tiff" -> startsWith(head, len, TIF_LE) || startsWith(head, len, TIF_BE);
            case "xlsx", "docx", "pptx", "hwpx", "zip" -> startsWith(head, len, ZIP) || startsWith(head, len, ZIP_EMPTY);
            case "xls", "doc", "ppt", "hwp" -> startsWith(head, len, OLE);
            case "dwg" -> startsWith(head, len, DWG);
            default -> TEXT_TYPES.contains(ext) ? looksLikeText(head, len) : !startsWith(head, len, SHEBANG);
        };
    }

    /** NUL 바이트가 없고 스크립트 시작(#!)·압축·OLE 가 아니면 텍스트로 본다 (UTF-8·CP949 모두 허용) */
    private static boolean looksLikeText(byte[] head, int len) {
        if (startsWith(head, len, SHEBANG) || startsWith(head, len, ZIP) || startsWith(head, len, OLE)) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (head[i] == 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(byte[] head, int len, byte[] sig) {
        return len >= sig.length && regionEquals(head, 0, sig);
    }

    private static boolean regionEquals(byte[] head, int offset, byte[] sig) {
        for (int i = 0; i < sig.length; i++) {
            if (head[offset + i] != sig[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}

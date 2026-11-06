package com.ksaleshunter.be.upload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 업로드·생성 이미지 파일 저장소 (로컬 디스크).
 *
 * <p>AI 서비스와 같은 디렉터리를 쓴다(AI 의 DEV_BFF_UPLOAD_DIR = 여기 storage.upload-dir).
 * 그래서 공개 URL 형식도 AI 와 같은 {@code /api/v1/uploads/{name}} 이다. AI 는 참고 사진을 이 URL 에서
 * 이름만 떼어 같은 디렉터리에서 읽고, 생성 이미지도 같은 디렉터리에 같은 형식의 URL 로 돌려준다.
 * 운영에서 S3 로 바꾸면 이 클래스만 교체한다.
 *
 * <p>상품 등록의 {@code POST /uploads/images}(products·uploads 담당)도 이 빈을 쓰면 된다.
 */
@Component
public class UploadStorage {

	public static final String URL_PREFIX = "/api/v1/uploads/";

	private static final Set<String> ALLOWED = Set.of("png", "jpg", "jpeg", "webp", "gif");

	private final Path dir;

	public UploadStorage(@Value("${storage.upload-dir}") String dir) {
		this.dir = Path.of(dir).toAbsolutePath().normalize();
	}

	public record Stored(String name, String url) {
	}

	public Stored save(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "파일이 비어 있습니다");
		}
		String original = Optional.ofNullable(file.getOriginalFilename()).orElse("image.png");
		String suffix = original.contains(".")
			? original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "png";
		if (!ALLOWED.contains(suffix)) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "이미지 파일만 올릴 수 있습니다");
		}
		String name = UUID.randomUUID().toString().replace("-", "") + "." + suffix;
		try (InputStream in = file.getInputStream()) {
			Files.createDirectories(dir);
			Files.copy(in, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException e) {
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "파일 저장 실패", e);
		}
		return new Stored(name, URL_PREFIX + name);
	}

	/** 파일 이름 → 경로. 디렉터리 밖을 가리키거나 없으면 비어 있다. */
	public Optional<Path> resolve(String name) {
		Path path = dir.resolve(Path.of(name).getFileName().toString()).normalize();
		return path.startsWith(dir) && Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
	}

	/** 업로드 id(= 파일 이름)를 공개 URL 로. 파일이 없으면 비어 있다. */
	public Optional<String> urlOf(String name) {
		return resolve(name).map(path -> URL_PREFIX + path.getFileName());
	}
}

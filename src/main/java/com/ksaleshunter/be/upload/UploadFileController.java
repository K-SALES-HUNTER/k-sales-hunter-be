package com.ksaleshunter.be.upload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 업로드·AI 생성 이미지 서빙. {@code GET /api/v1/uploads/{name}}.
 * 파일 이름이 무작위 해시라 바뀌지 않으므로 오래 캐시한다.
 */
@RestController
public class UploadFileController {

	private final UploadStorage storage;

	public UploadFileController(UploadStorage storage) {
		this.storage = storage;
	}

	@GetMapping("/uploads/{name}")
	public ResponseEntity<Resource> serve(@PathVariable String name) throws IOException {
		Path path = storage.resolve(name)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "파일이 없습니다"));
		MediaType type = MediaTypeFactory.getMediaType(path.getFileName().toString())
			.orElse(MediaType.APPLICATION_OCTET_STREAM);
		return ResponseEntity.ok()
			.contentType(type)
			.contentLength(Files.size(path))
			.cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
			.body(new FileSystemResource(path));
	}
}

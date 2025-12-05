@RestController
@RequestMapping("/files")
public class FileProxyController {

    private final FileProxyService fileProxyService;

    public FileProxyController(FileProxyService fileProxyService) {
        this.fileProxyService = fileProxyService;
    }

    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> downloadFile(
            @RequestParam String bucket,
            @RequestParam String filename
    ) {

        // 通过 Feign 从 Service A 拉取文件
        feign.Response feignResponse = fileProxyService.downloadFromServiceA(bucket, filename);

        if (feignResponse == null || feignResponse.status() == 404) {
            return ResponseEntity.notFound().build();
        }

        if (feignResponse.status() >= 400) {
            // 可以根据实际情况加更多错误处理
            return ResponseEntity.status(feignResponse.status()).build();
        }

        StreamingResponseBody body = outputStream -> {
            // 一定要在 StreamingResponseBody 内部读取 InputStream，保持连接存活
            try (feign.Response response = feignResponse;
                 InputStream inputStream = response.body().asInputStream()) {

                byte[] buffer = new byte[8192]; // 8KB 缓冲区
                int len;
                while ((len = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, len);
                }
                // 可根据情况 flush（通常不写也行）
                outputStream.flush();
            }
        };

        HttpHeaders headers = new HttpHeaders();

        // 拷贝 Content-Type
        String contentType = getFirstHeader(feignResponse, HttpHeaders.CONTENT_TYPE);
        if (contentType == null || contentType.isBlank()) {
            contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        headers.setContentType(MediaType.parseMediaType(contentType));

        // 拷贝 Content-Disposition（保留原文件名）
        String contentDisposition = getFirstHeader(feignResponse, HttpHeaders.CONTENT_DISPOSITION);
        if (contentDisposition == null || contentDisposition.isBlank()) {
            contentDisposition = "attachment; filename=" + filename;
        }
        headers.set(HttpHeaders.CONTENT_DISPOSITION, contentDisposition);

        // 拷贝 Content-Length（如果有）
        String contentLength = getFirstHeader(feignResponse, HttpHeaders.CONTENT_LENGTH);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok().headers(headers);
        if (contentLength != null) {
            try {
                builder.contentLength(Long.parseLong(contentLength));
            } catch (NumberFormatException ignored) {
            }
        }

        return builder.body(body);
    }

    private String getFirstHeader(feign.Response response, String headerName) {
        Map<String, Collection<String>> headers = response.headers();
        Collection<String> values = headers.get(headerName);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.iterator().next();
    }
}


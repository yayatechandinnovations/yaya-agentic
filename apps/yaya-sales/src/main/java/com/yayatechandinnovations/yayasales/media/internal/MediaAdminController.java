package com.yayatechandinnovations.yayasales.media.internal;

import com.yayatechandinnovations.yayasales.media.MediaService;
import com.yayatechandinnovations.yayasales.media.MediaView;
import com.yayatechandinnovations.yayasales.platform.Ids.MediaId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Media upload/listing for the console (design §10.1). Serving is public — see MediaController. */
@RestController
@RequestMapping("/v1/sales/admin/media")
public class MediaAdminController {

    private static final String STORE_HEADER = "X-Yaya-Store-Id";

    private final MediaService media;

    public MediaAdminController(MediaService media) {
        this.media = media;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> upload(@RequestHeader(STORE_HEADER) String storeId,
                                      @RequestParam("file") MultipartFile file) throws IOException {
        MediaView view = media.upload(new StoreId(storeId), file.getOriginalFilename(),
                file.getContentType(), file.getBytes());
        return Map.of("media", view, "url", view.path());
    }

    @GetMapping
    public List<MediaView> list(@RequestHeader(STORE_HEADER) String storeId) {
        return media.mediaOf(new StoreId(storeId));
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@RequestHeader(STORE_HEADER) String storeId,
                                      @PathVariable String id) {
        media.delete(new MediaId(id), new StoreId(storeId));
        return Map.of("status", "deleted");
    }
}

package com.yayatechandinnovations.yayasales.media.internal;

import com.yayatechandinnovations.yayasales.media.MediaService;
import com.yayatechandinnovations.yayasales.platform.Ids.MediaId;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Public image serving — product photos and catalog covers render in the
 * storefront chat and the console alike. Ids are random 128-bit, media is
 * immutable once uploaded, so responses cache hard.
 */
@RestController
public class MediaController {

    private final MediaService media;

    public MediaController(MediaService media) {
        this.media = media;
    }

    @GetMapping("/v1/sales/media/{id}")
    public ResponseEntity<byte[]> serve(@PathVariable String id) {
        MediaService.Stored stored = media.load(new MediaId(id))
                .orElseThrow(() -> new ApiErrors.NotFoundException("media", id));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stored.meta().contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).immutable().cachePublic())
                .body(stored.bytes());
    }
}

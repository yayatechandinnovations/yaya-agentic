package com.yayatechandinnovations.yayaagentic.channel.system;

/**
 * A piece of media on a message, sealed so every renderer / provider {@code
 * switch} is forced open when a kind is added (§10 #4). Carries the logical
 * media-asset id + type facts; the signed short-lived URL is minted at delivery,
 * never stored on the attachment.
 */
public sealed interface Attachment
        permits Attachment.Image, Attachment.Video, Attachment.Audio, Attachment.Document {

    String assetId();
    String contentType();
    long sizeBytes();
    String kindName();

    record Image(String assetId, String contentType, long sizeBytes) implements Attachment {
        @Override public String kindName() { return "IMAGE"; }
    }

    record Video(String assetId, String contentType, long sizeBytes) implements Attachment {
        @Override public String kindName() { return "VIDEO"; }
    }

    record Audio(String assetId, String contentType, long sizeBytes) implements Attachment {
        @Override public String kindName() { return "AUDIO"; }
    }

    record Document(String assetId, String contentType, long sizeBytes, String filename)
            implements Attachment {
        @Override public String kindName() { return "DOCUMENT"; }
    }

    /** Classify by MIME into the right attachment kind. */
    static Attachment of(String assetId, String contentType, long sizeBytes, String filename) {
        String ct = contentType == null ? "" : contentType.toLowerCase();
        if (ct.startsWith("image/")) return new Image(assetId, contentType, sizeBytes);
        if (ct.startsWith("video/")) return new Video(assetId, contentType, sizeBytes);
        if (ct.startsWith("audio/")) return new Audio(assetId, contentType, sizeBytes);
        return new Document(assetId, contentType, sizeBytes,
                filename == null || filename.isBlank() ? "document" : filename);
    }
}

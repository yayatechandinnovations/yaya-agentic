import 'package:flutter/material.dart';

import '../media/image_picker.dart';
import '../media/media_service.dart';
import 'widgets.dart';

/// Pick-upload-preview for image lists: product photos (multiple) or a
/// catalog cover (`maxImages: 1`). Persists only the serving URLs — the
/// caller decides where they land (media_json, cover_url).
class ImageManager extends StatefulWidget {
  const ImageManager({
    super.key,
    required this.urls,
    required this.onChanged,
    required this.media,
    required this.picker,
    this.maxImages,
    this.thumbSize = 72,
  });

  final List<String> urls;
  final ValueChanged<List<String>> onChanged;
  final MediaService media;
  final ImagePicker picker;
  final int? maxImages;
  final double thumbSize;

  @override
  State<ImageManager> createState() => _ImageManagerState();
}

class _ImageManagerState extends State<ImageManager> {
  bool _uploading = false;

  Future<void> _add() async {
    final picked = await widget.picker.pickImage();
    if (picked == null || !mounted) return;
    setState(() => _uploading = true);
    try {
      final url = await widget.media
          .upload(picked.bytes, picked.filename, picked.contentType);
      if (!mounted) return;
      final next = [...widget.urls, url];
      widget.onChanged(widget.maxImages == 1 ? [url] : next);
    } catch (e) {
      if (mounted) showErrorSnack(context, 'Upload failed: $e');
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final canAdd = !_uploading &&
        (widget.maxImages == null || widget.urls.length < widget.maxImages!
            || widget.maxImages == 1);
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        for (final url in widget.urls)
          Stack(
            clipBehavior: Clip.none,
            children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(8),
                child: Image.network(
                  widget.media.absolute(url),
                  width: widget.thumbSize,
                  height: widget.thumbSize,
                  fit: BoxFit.cover,
                  errorBuilder: (_, __, ___) => Container(
                    width: widget.thumbSize,
                    height: widget.thumbSize,
                    color: Colors.black12,
                    child: const Icon(Icons.broken_image_outlined, size: 20),
                  ),
                ),
              ),
              Positioned(
                top: -6,
                right: -6,
                child: InkWell(
                  onTap: () => widget
                      .onChanged(widget.urls.where((u) => u != url).toList()),
                  child: const CircleAvatar(
                    radius: 10,
                    backgroundColor: Colors.black54,
                    child: Icon(Icons.close, size: 12, color: Colors.white),
                  ),
                ),
              ),
            ],
          ),
        if (canAdd)
          OutlinedButton.icon(
            onPressed: _add,
            icon: _uploading
                ? const SizedBox(
                    width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.add_photo_alternate_outlined, size: 18),
            label: Text(widget.maxImages == 1
                ? (widget.urls.isEmpty ? 'Add cover' : 'Replace cover')
                : 'Add image'),
          ),
      ],
    );
  }
}

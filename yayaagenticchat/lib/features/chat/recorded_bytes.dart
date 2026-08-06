// Read the bytes a finished recording left behind. On web the recorder hands
// back a `blob:` URL — read via the browser's native fetch (Firefox forbids
// XHR on blob: URLs, which is why a Dio/XHR read comes back empty there);
// on mobile/desktop it's a file path.
export 'recorded_bytes_io.dart' if (dart.library.js_interop) 'recorded_bytes_web.dart';

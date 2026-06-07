# syntax=docker/dockerfile:1.7

# ---- Build stage ------------------------------------------------------
# Cirrus Labs ships the official Flutter SDK on Ubuntu. `stable` tracks
# the current stable channel; pin a specific tag (e.g. 3.40.0) in CI
# to make builds reproducible. pubspec.yaml requires Dart SDK ^3.11.0.
FROM ghcr.io/cirruslabs/flutter:stable AS build
WORKDIR /workspace

# Cirrus Labs flutter image refuses to run as root by default; the safety
# check is intended for desktop dev, not isolated CI builds.
ENV FLUTTER_SUPPRESS_ANALYTICS=1 \
    PUB_CACHE=/var/cache/pub

# Resolve pub deps first so they cache across source-only edits.
COPY pubspec.yaml pubspec.lock ./
RUN git config --global --add safe.directory /sdks/flutter \
    && flutter --version \
    && flutter pub get

# Now the rest of the source.
COPY . ./

# Operator-overridable backend URL baked into the JS bundle at build time.
# The default points at the host-exposed backend port so a developer
# running compose locally has zero-config browsing.
ARG BACKEND_BASE_URL=http://localhost:8080
RUN flutter build web --release \
        --dart-define=BACKEND_BASE_URL=${BACKEND_BASE_URL}

# ---- Runtime stage ----------------------------------------------------
FROM nginx:1.27-alpine AS runtime

# Static site only — no upstream proxy. The Flutter app talks directly to
# the backend host:port from the user's browser.
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/build/web /usr/share/nginx/html

EXPOSE 80

# Build image for Thor D2: the shared "thor-build" image (JDK 17, Android SDK 35, Gradle)
# plus what D2Android's source/README.md asks for.
FROM thor-build
RUN apt-get update -q && apt-get install -y -q --no-install-recommends \
        gcc-mingw-w64-i686 binutils-mingw-w64-i686 gcc-aarch64-linux-gnu libc6-dev-arm64-cross \
        libasound2-dev libx11-dev zstd xz-utils curl unzip python3 python3-pip \
    && rm -rf /var/lib/apt/lists/*
RUN pip3 install -q zstandard
RUN yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk \
        "ndk;24.0.8215888" "cmake;3.22.1" "build-tools;34.0.0" >/dev/null

FROM ghcr.io/pterodactyl/yolks:java_25

USER root

RUN apt-get update \
    && apt-get install -y --no-install-recommends btrfs-progs fuse-overlayfs \
    && rm -rf /var/lib/apt/lists/*

USER container

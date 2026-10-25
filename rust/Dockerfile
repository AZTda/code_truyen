FROM rust:1-slim-bookworm AS builder
RUN apt-get update && apt-get install -y --no-install-recommends pkg-config libssl-dev build-essential && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY Cargo.toml Cargo.lock ./
COPY crates ./crates
RUN cargo build --release -p truyencloud-server

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /workspace/target/release/truyencloud-server /usr/local/bin/truyencloud-server
ENV TRUYENCLOUD_SERVER_DIR=/app
ENV TRUYENCLOUD_DATA_DIR=/app/data
ENV TRUYENCLOUD_STATIC_DIR=/app/static
ENV TRUYENCLOUD_RUST_PORT=8000
ENV TRUYENCLOUD_QWEN_URL=http://truyencloud-qwen:8081/v1/chat/completions
EXPOSE 8000
CMD ["/usr/local/bin/truyencloud-server"]

VERSION := 0.1.0
LDFLAGS := -s -w
GOBUILD := go build -trimpath -ldflags="$(LDFLAGS)"

.PHONY: all test host mango mips aarch64 ipk clean

all: test mango mips aarch64 ipk

test:
	gofmt -l . && go vet ./... && go test ./...

host:
	$(GOBUILD) -o dist/opendmo .

# GL.iNet Mango (GL-MT300N-V2, ramips/mt76x8) y en general mipsel_24kc
mango:
	GOOS=linux GOARCH=mipsle GOMIPS=softfloat $(GOBUILD) -o dist/opendmo-mango .

# ath79 y demás MIPS big-endian (mips_24kc)
mips:
	GOOS=linux GOARCH=mips GOMIPS=softfloat $(GOBUILD) -o dist/opendmo-mips .

# GL.iNet aarch64 (MT2500/AXT1800…, aarch64_cortex-a53) y Raspberry Pi
aarch64:
	GOOS=linux GOARCH=arm64 $(GOBUILD) -o dist/opendmo-aarch64 .

ipk: mango mips aarch64
	./openwrt/build-ipk.sh mipsel_24kc dist/opendmo-mango $(VERSION)
	./openwrt/build-ipk.sh mips_24kc dist/opendmo-mips $(VERSION)
	./openwrt/build-ipk.sh aarch64_cortex-a53 dist/opendmo-aarch64 $(VERSION)

clean:
	rm -rf dist

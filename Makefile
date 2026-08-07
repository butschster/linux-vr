# linux-vr — a Linux desktop, in a VR headset.
#
# Two halves that are built and installed separately: a Go server on the machine
# whose desktop you want, and an Android client in the headset.

SERVER      := linux-vr-server
VERSION     ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)
PREFIX      ?= /usr/local
BINDIR      := $(PREFIX)/bin
UNITDIR     := $(HOME)/.config/systemd/user
APK         := client/app/build/outputs/apk/debug/app-debug.apk
PACKAGE     := dev.butschster.linuxvr

.PHONY: all server client install install-server install-service uninstall \
        run doctor monitors apk dev fmt test clean help

all: server client

help:
	@echo "make server           build the Go server into host/$(SERVER)"
	@echo "make install          install the server and its systemd user service"
	@echo "make run              build and run the server in this terminal"
	@echo "make doctor           check this machine has what the server needs"
	@echo "make client           build the Android client"
	@echo "make apk              build the client and install it on the attached headset"
	@echo "make dev              server in the foreground plus a fresh client on the headset"
	@echo "make uninstall        remove the server, the service and the app"

# ------------------------------------------------------------------- the server

server:
	cd host && go build -trimpath \
	    -ldflags "-s -w -X main.version=$(VERSION)" \
	    -o $(SERVER) ./cmd/$(SERVER)

fmt:
	cd host && gofmt -w ./cmd ./internal && go vet ./...

test:
	cd host && go test ./...

run: server
	./host/$(SERVER)

doctor: server
	./host/$(SERVER) doctor

monitors: server
	./host/$(SERVER) monitors

install: install-server install-service
	@echo
	@echo "Installed. The headset should find this machine by itself;"
	@echo "if it does not, add $$(hostname -I | awk '{print $$1}'):9099 by hand."

install-server: server
	install -Dm755 host/$(SERVER) $(BINDIR)/$(SERVER)

# A user service, not a system one: it needs the session bus to ask mutter about
# monitors, and the clipboard it pastes into is yours. Root has neither.
install-service:
	@mkdir -p $(UNITDIR)
	@sed "s|@BINDIR@|$(BINDIR)|g; s|@HOME@|$(HOME)|g" \
	    packaging/$(SERVER).service > $(UNITDIR)/$(SERVER).service
	systemctl --user daemon-reload
	systemctl --user enable --now $(SERVER).service
	@systemctl --user --no-pager --lines=0 status $(SERVER).service || true

# ------------------------------------------------------------------- the client

client:
	cd client && ./gradlew :app:assembleDebug

apk: client
	adb install -r $(APK)

dev: apk
	adb shell am force-stop $(PACKAGE) || true
	adb shell am start -n $(PACKAGE)/.ServersActivity
	$(MAKE) run

# ------------------------------------------------------------------ removing it

uninstall:
	-systemctl --user disable --now $(SERVER).service
	-rm -f $(UNITDIR)/$(SERVER).service
	-systemctl --user daemon-reload
	-rm -f $(BINDIR)/$(SERVER)
	-adb uninstall $(PACKAGE)

clean:
	rm -f host/$(SERVER)
	cd client && ./gradlew clean

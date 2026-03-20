//go:build android

package main

//#include <stdlib.h>
import "C"

import (
	"net"
	"os"

	"tailscale.com/net/netmon"
)

// TsnetSetAndroidDirs must be called before TsnetNewServer on Android to ensure
// Go's os/logpolicy can find writable directories.
//
//export TsnetSetAndroidDirs
func TsnetSetAndroidDirs(dir *C.char) {
	goDir := C.GoString(dir)
	if err := os.MkdirAll(goDir, 0755); err != nil {
		return
	}
	os.Setenv("TS_LOGS_DIR", goDir)
	os.Setenv("HOME", goDir)
	os.Setenv("TMPDIR", goDir)
}

// init registers a synthetic interface getter so netmon reports v4=true
// and does not pause the Tailscale control client.
//
// Android SELinux blocks /proc/net/* and /sys/class/net/ for untrusted apps,
// so we cannot read real interface addresses. Instead we return a fake "android0"
// interface with a private IPv4 address — netmon only needs isUsableV4()=true to
// unpause auth. The actual network path used by userspace WireGuard is separate.
func init() {
	netmon.RegisterInterfaceGetter(androidInterfaces)
}

func androidInterfaces() ([]netmon.Interface, error) {
	lo := &net.Interface{Index: 1, MTU: 65536, Name: "lo", Flags: net.FlagUp | net.FlagLoopback}
	wan := &net.Interface{
		Index: 2,
		Name:  "android0",
		MTU:   1500,
		Flags: net.FlagUp | net.FlagMulticast,
	}
	// A private RFC 1918 address makes isUsableV4() return true.
	wanIP := &net.IPNet{IP: net.IPv4(10, 0, 0, 1), Mask: net.CIDRMask(8, 32)}
	return []netmon.Interface{
		{Interface: lo, AltAddrs: []net.Addr{}},
		{Interface: wan, AltAddrs: []net.Addr{wanIP}},
	}, nil
}

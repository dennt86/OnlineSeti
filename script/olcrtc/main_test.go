package main

import (
	"encoding/json"
	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/client"
	"strings"
	"testing"
)

func fixture() config {
	return config{Provider: "jitsi", Transport: "datachannel", Room: "https://example.org/room", Key: strings.Repeat("01", 32), DNS: "8.8.8.8:53", SOCKS: "127.0.0.1:1080", DeviceID: "persistent-test-device"}
}
func TestOptionsAndIdentity(t *testing.T) {
	for _, transport := range []string{"datachannel", "vp8channel", "seichannel", "videochannel"} {
		c := fixture()
		c.Transport = transport
		c.Options = map[string]json.RawMessage{"vp8-fps": json.RawMessage(`"60"`), "video-codec": json.RawMessage(`"tile"`)}
		got, err := makeClient(c)
		if err != nil {
			t.Fatal(err)
		}
		if got.DeviceID != c.DeviceID || got.RoomURL != c.Room || got.KeyHex != c.Key || got.LocalAddr != c.SOCKS {
			t.Fatal("session identity or endpoint changed")
		}
		switch transport {
		case "vp8channel":
			if got.TransportOptions.(client.VP8Options).FPS != 60 {
				t.Fatal("VP8 URI option lost")
			}
		case "seichannel":
			if got.TransportOptions.(client.SEIOptions).FragmentSize != 900 {
				t.Fatal("SEI default lost")
			}
		case "videochannel":
			if got.TransportOptions.(client.VideoOptions).Codec != "tile" {
				t.Fatal("video option lost")
			}
		}
	}
}
func TestRejectsUnsupportedAndUnsafeConfigurations(t *testing.T) {
	changes := []func(*config){func(c *config) { c.Provider = "telemost" }, func(c *config) { c.Provider = "wbstream" }, func(c *config) { c.SOCKS = "0.0.0.0:1080" }, func(c *config) { c.DeviceID = "" }, func(c *config) { c.Key = "short" }, func(c *config) {
		c.Transport = "videochannel"
		c.Options = map[string]json.RawMessage{"video-w": json.RawMessage(`1`), "video-codec": json.RawMessage(`"tile"`)}
	}}
	for _, change := range changes {
		c := fixture()
		change(&c)
		if _, err := makeClient(c); err == nil {
			t.Fatal("invalid configuration accepted")
		}
	}
	c := fixture()
	c.Provider = "wbstream"
	c.Token = "test-publish-token"
	if _, err := makeClient(c); err != nil {
		t.Fatal("moderator-token mode rejected")
	}
}

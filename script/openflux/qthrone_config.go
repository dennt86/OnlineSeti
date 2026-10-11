package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/p1neappleXpress/OpenFlux/share"
)

// qThrone owns the private configuration directory and the process lifetime.
// Keep secrets in files, never in command-line arguments or diagnostic output.
func init() {
	if len(os.Args) != 3 || os.Args[1] != "--qthrone-config" {
		return
	}
	data, err := os.ReadFile(os.Args[2])
	if err != nil || len(data) > 65536 {
		log.Fatal("OpenFlux: cannot read private configuration")
	}
	conf, key, err := qThroneConfig(data, filepath.Dir(os.Args[2]))
	if err != nil {
		log.Fatal("OpenFlux: invalid client configuration")
	}
	folder := filepath.Dir(os.Args[2])
	keyPath, confPath := filepath.Join(folder, "openflux.key"), filepath.Join(folder, "openflux.conf")
	if os.WriteFile(keyPath, []byte(key), 0600) != nil || os.WriteFile(confPath, []byte(conf), 0600) != nil {
		log.Fatal("OpenFlux: cannot create private configuration")
	}
	os.Args = []string{os.Args[0], "--config", confPath, "--negotiate"}
	go func() {
		scanner := bufio.NewScanner(io.LimitReader(os.Stdin, 1024))
		for scanner.Scan() {
			if strings.TrimSpace(scanner.Text()) == "STOP" {
				os.Exit(0)
			}
		}
		os.Exit(0)
	}()
}

func qThroneConfig(data []byte, folder string) (string, string, error) {
	var c struct {
		share.Config
		SOCKS string `json:"socks"`
	}
	if json.Unmarshal(data, &c) != nil || c.Validate() != nil || c.Mode != "" || !c.Negotiate {
		return "", "", errors.New("invalid session")
	}
	host, port, err := net.SplitHostPort(c.SOCKS)
	n, _ := strconv.Atoi(port)
	if err != nil || host != "127.0.0.1" || n < 1 || n > 65535 {
		return "", "", errors.New("invalid listener")
	}
	safe := func(s string) bool { return !strings.ContainsAny(s, "\r\n#;") }
	if !safe(c.Context) || strings.ContainsAny(c.Secret, "\r\n") || !safe(folder) {
		return "", "", errors.New("invalid private parameters")
	}
	var conf strings.Builder
	codec := c.Codec
	if codec == "" {
		codec = "batched"
	}
	fmt.Fprintf(&conf, "[Interface]\nRole = client\nInbound = socks5\nMode = l4\nCodec = %s\nSocks5 = %s\nEncryptionKeyFile = %s\nSessionContext = %s\nURL = %s\n", codec, c.SOCKS, filepath.Join(folder, "openflux.key"), c.Context, c.Context)
	seen := make(map[string]bool)
	for _, t := range c.Transports {
		if seen[t.Type] || (t.Name != "" && t.Name != t.Type) || !safe(t.URL) || !safe(t.Dial) {
			return "", "", errors.New("invalid transport")
		}
		seen[t.Type] = true
		switch t.Type {
		case "direct", "yandex", "mailru", "cupsonline":
		default:
			return "", "", errors.New("unsupported transport")
		}
		fmt.Fprintf(&conf, "\n[Transport \"%s\"]\nType = %s\nPriority = %d\n", t.Type, t.Type, t.Priority)
		if t.Type == "direct" {
			addr, p, err := net.SplitHostPort(t.Dial)
			number, _ := strconv.Atoi(p)
			if err != nil || addr == "" || number < 1 || number > 65535 {
				return "", "", errors.New("invalid direct address")
			}
			fmt.Fprintf(&conf, "Dial = %s\n", t.Dial)
		} else {
			fmt.Fprintf(&conf, "URL = %s\n", t.URL)
		}
	}
	return conf.String(), c.Secret, nil
}

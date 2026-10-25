package main

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// slipstream runs slipstream-client (Rust) as a child. The mobile build of the
// client listens on a Unix socket instead of TCP, inside the private session
// directory, so no other app can open a stream through it.
type slipstream struct {
	cmd    *exec.Cmd
	socket string
	done   chan struct{}
}

func startSlipstream(ctx context.Context, c config, configPath string) (*slipstream, error) {
	exe, err := os.Executable()
	if err != nil {
		return nil, err
	}
	folder, err := filepath.Abs(filepath.Dir(configPath))
	if err != nil {
		return nil, err
	}
	// Socket paths are limited to 108 bytes; the session directory alone comes
	// close on Android, so both processes use the name relative to it.
	if err := os.Chdir(folder); err != nil {
		return nil, err
	}
	socket := "slipstream.sock"
	args := []string{"--tcp-listen-host", socket, "--tcp-listen-port", "1", "--domain", c.Domain}
	for _, r := range c.resolverList() {
		r = strings.TrimPrefix(strings.TrimSpace(r), "udp://")
		if strings.Contains(r, "://") {
			return nil, fmt.Errorf("Slipstream resolvers are UDP addresses, not %q", r)
		}
		flag := "--resolver"
		if c.Authoritative {
			flag = "--authoritative"
		}
		args = append(args, flag, withPort(r, "53"))
	}
	if cert := strings.TrimSpace(c.Cert); cert != "" {
		path := filepath.Join(folder, "slipstream.pem")
		if err := os.WriteFile(path, []byte(pemCert(cert)), 0o600); err != nil {
			return nil, err
		}
		args = append(args, "--cert", path)
	}
	switch c.Congestion {
	case "bbr", "dcubic":
		args = append(args, "--congestion-control", c.Congestion)
	}
	if c.KeepAliveMillis > 0 && c.KeepAliveMillis <= 65535 {
		args = append(args, "--keep-alive-interval", strconv.Itoa(c.KeepAliveMillis))
	}
	cmd := exec.CommandContext(ctx, filepath.Join(filepath.Dir(exe), "libslipstream.so"), args...)
	cmd.Dir = folder
	output, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	cmd.Stderr = cmd.Stdout
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("cannot start the Slipstream client: %w", err)
	}
	s := &slipstream{cmd: cmd, socket: socket, done: make(chan struct{})}
	go func() {
		scanner := bufio.NewScanner(io.LimitReader(output, 1<<30))
		for scanner.Scan() {
			fmt.Println("[Slipstream] " + scanner.Text())
		}
	}()
	go func() { _ = cmd.Wait(); close(s.done) }()
	deadline := time.Now().Add(10 * time.Second)
	for {
		if _, err := os.Stat(socket); err == nil {
			return s, nil
		}
		select {
		case <-s.done:
			return nil, errors.New("the Slipstream client stopped before listening")
		case <-time.After(50 * time.Millisecond):
		}
		if time.Now().After(deadline) {
			s.Close()
			return nil, errors.New("the Slipstream client did not start")
		}
	}
}

func (s *slipstream) Open(ctx context.Context) (net.Conn, error) {
	select {
	case <-s.done:
		return nil, errors.New("the Slipstream client stopped")
	default:
	}
	var d net.Dialer
	return d.DialContext(ctx, "unix", s.socket)
}

func (s *slipstream) Close() error {
	if s.cmd.Process != nil {
		_ = s.cmd.Process.Signal(os.Interrupt)
		select {
		case <-s.done:
		case <-time.After(2 * time.Second):
			_ = s.cmd.Process.Kill()
			<-s.done
		}
	}
	return os.Remove(s.socket)
}

// pemCert restores a certificate shared as base64url DER (dnst:// cert).
func pemCert(cert string) string {
	if strings.Contains(cert, "-----BEGIN") {
		return cert
	}
	body := strings.NewReplacer("-", "+", "_", "/").Replace(strings.TrimSpace(cert))
	for len(body)%4 != 0 {
		body += "="
	}
	var b strings.Builder
	b.WriteString("-----BEGIN CERTIFICATE-----\n")
	for len(body) > 64 {
		b.WriteString(body[:64] + "\n")
		body = body[64:]
	}
	b.WriteString(body + "\n-----END CERTIFICATE-----\n")
	return b.String()
}

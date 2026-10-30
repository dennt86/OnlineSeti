package main

import (
	"archive/zip"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
)

// The release workflow overrides these values for branded editions.
var productName = "qThrone"
var coreName = "qThroneCore"
var archiveName = "qThrone.zip"

func extract(archive, stage string) error {
	z, err := zip.OpenReader(archive)
	if err != nil {
		return err
	}
	defer z.Close()
	for _, f := range z.File {
		name := strings.TrimPrefix(f.Name, productName+"/")
		if f.Name == productName+"/" {
			continue
		}
		if name == f.Name || strings.Contains(name, "\\") || filepath.IsAbs(name) || filepath.Clean(name) == ".." || strings.HasPrefix(filepath.Clean(name), ".."+string(filepath.Separator)) || f.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("invalid archive entry %q", f.Name)
		}
		target := filepath.Join(stage, name)
		if f.FileInfo().IsDir() {
			if err := os.MkdirAll(target, 0755); err != nil {
				return err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(target), 0755); err != nil {
			return err
		}
		if err := extractFile(f, target); err != nil {
			return err
		}
	}
	return nil
}

func extractFile(f *zip.File, target string) error {
	in, err := f.Open()
	if err != nil {
		return err
	}
	defer in.Close()
	mode := f.Mode().Perm()
	if mode == 0 {
		mode = 0644
	}
	out, err := os.OpenFile(target, os.O_CREATE|os.O_EXCL|os.O_WRONLY, mode)
	if err != nil {
		return err
	}
	_, copyErr := io.Copy(out, in)
	closeErr := out.Close()
	if copyErr != nil {
		return copyErr
	}
	return closeErr
}

func update() error {
	if len(os.Args) > 1 {
		pid, err := strconv.Atoi(os.Args[1])
		if err != nil || pid <= 0 {
			return fmt.Errorf("invalid parent PID")
		}
		if err := waitParent(pid); err != nil {
			return err
		}
	}
	stage, err := os.MkdirTemp(".", ".onlineseti-update-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(stage)
	if err := extract(archiveName, stage); err != nil {
		return err
	}
	executable := productName
	core := coreName
	if runtime.GOOS == "windows" {
		executable += ".exe"
		core += ".exe"
	}
	for _, name := range []string{executable, core} {
		if info, err := os.Stat(filepath.Join(stage, name)); err != nil || !info.Mode().IsRegular() {
			return fmt.Errorf("missing release executable %s", name)
		}
	}
	backup, err := os.MkdirTemp(".", ".onlineseti-backup-")
	if err != nil {
		return err
	}
	entries, err := os.ReadDir(stage)
	if err != nil {
		return err
	}
	var installed, saved []string
	rollback := func() {
		for _, name := range installed {
			os.RemoveAll(name)
		}
		for _, name := range saved {
			os.Rename(filepath.Join(backup, name), name)
		}
	}
	for _, entry := range entries {
		name := entry.Name()
		// Portable user data is never part of a release archive.
		if name == "config" || name == "profiles" || strings.HasSuffix(name, ".db") {
			rollback()
			return fmt.Errorf("release contains user data")
		}
		if _, err := os.Lstat(name); err == nil {
			if err := os.Rename(name, filepath.Join(backup, name)); err != nil {
				rollback()
				return fmt.Errorf("backup %s: %w", name, err)
			}
			saved = append(saved, name)
		} else if !os.IsNotExist(err) {
			rollback()
			return err
		}
		if err := os.Rename(filepath.Join(stage, name), name); err != nil {
			rollback()
			return err
		}
		installed = append(installed, name)
	}
	os.RemoveAll(backup)
	os.Remove(archiveName)
	return exec.Command("./" + executable).Start()
}

func main() {
	if err := update(); err != nil {
		message := fmt.Sprintf("%s update failed: %v\n", productName, err)
		os.WriteFile(productName+"-update-error.log", []byte(message), 0600)
		fmt.Fprint(os.Stderr, message)
		os.Exit(1)
	}
}

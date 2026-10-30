package rpc

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"strings"
	"time"

	"ThroneCore/gen"
	"ThroneCore/internal/process"
	"github.com/google/shlex"
)

// All entries are owned by the running configuration, under lifecycleMu.
type extraSession struct {
	child     *process.Process
	key       [32]byte
	transport bool
	tag       string
	release   func()
}

var extraSessions []*extraSession

func isTunnelHelper(path string) bool {
	name := strings.TrimSuffix(strings.ToLower(filepath.Base(path)), ".exe")
	return name == "qwdtt" || name == "onlinesetiwdtt" || name == "wdttplus" || name == "onlinesetiwdttplus" || name == "openflux" || name == "onlinesetiopenflux" || name == "olcrtc" || name == "onlinesetiolcrtc"
}

func wdttPlusProcess(config string) (args []string, initial string, ok bool) {
	var value struct {
		Type  string   `json:"type"`
		Args  []string `json:"args"`
		Start any      `json:"start"`
	}
	if json.Unmarshal([]byte(config), &value) != nil || value.Type != "wdttplus" || len(value.Args) == 0 || value.Start == nil {
		return nil, "", false
	}
	encoded, err := json.Marshal(value.Start)
	if err != nil || len(encoded) > 24*1024 {
		return nil, "", false
	}
	return value.Args, "START_CONFIG|" + base64.RawURLEncoding.EncodeToString(encoded), true
}

func stopExtraSessions(tunnelsOnly bool) {
	for i := len(extraSessions) - 1; i >= 0; i-- {
		session := extraSessions[i]
		if tunnelsOnly && !session.transport {
			continue
		}
		session.child.Stop()
		if session.release != nil {
			session.release()
		}
		extraSessions = append(extraSessions[:i], extraSessions[i+1:]...)
	}
}

func activeTransportTag(config string) (string, bool) {
	key, valid := qwdttSessionKey(config)
	if !valid {
		return "", false
	}
	lifecycleMu.Lock()
	defer lifecycleMu.Unlock()
	for _, session := range extraSessions {
		if session.transport && session.key == key {
			return session.tag, true
		}
	}
	return "", false
}

func startExtraSessions(ctx context.Context, in *gen.LoadConfigReq) (err error) {
	specs := append([]*gen.RoutedExtraProcess(nil), in.GetRoutedExtraProcesses()...)
	if in.GetNeedExtraProcess() {
		specs = append([]*gen.RoutedExtraProcess{{Path: in.ExtraProcessPath, Args: in.ExtraProcessArgs,
			Config: in.ExtraProcessConf, NoOut: in.ExtraNoOut, OutboundTag: To("proxy")}}, specs...)
	}
	keys := make(map[[32]byte]bool)
	tags := make(map[string]bool)
	// Validate the complete list before starting any process.
	for i, spec := range specs {
		if spec == nil || spec.GetPath() == "" {
			return errors.New("missing extra process path")
		}
		if i >= 1 || !in.GetNeedExtraProcess() {
			if !isTunnelHelper(spec.GetPath()) {
				return errors.New("routing extra process must be a bundled tunnel helper")
			}
		}
		if isTunnelHelper(spec.GetPath()) {
			key, valid := qwdttSessionKey(spec.GetConfig())
			if !valid || spec.GetOutboundTag() == "" {
				return errors.New("invalid routed transport identity")
			}
			if keys[key] {
				return errors.New("the same tunnel device cannot run in two routing profiles")
			}
			if tags[spec.GetOutboundTag()] {
				return errors.New("duplicate routed transport tag")
			}
			keys[key] = true
			tags[spec.GetOutboundTag()] = true
		}
	}
	defer func() {
		if err != nil {
			stopExtraSessions(false)
		}
	}()
	for _, spec := range specs {
		args, e := shlex.Split(spec.GetArgs())
		if e != nil {
			return errors.New("failed to parse extra process arguments")
		}
		session := &extraSession{transport: isTunnelHelper(spec.GetPath()), tag: spec.GetOutboundTag()}
		if session.transport {
			session.key, _ = qwdttSessionKey(spec.GetConfig())
			gateCtx, cancel := context.WithTimeout(ctx, 100*time.Millisecond)
			session.release, e = acquireQWDTTProbe(gateCtx, session.key)
			cancel()
			if e != nil {
				return errors.New("tunnel device is busy with a test; stop its test and retry")
			}
		}
		config := spec.GetConfig()
		initialCommand := ""
		if strings.EqualFold(filepath.Base(strings.TrimSuffix(spec.GetPath(), ".exe")), "OnlineSetiWDTTPlus") || strings.EqualFold(filepath.Base(strings.TrimSuffix(spec.GetPath(), ".exe")), "wdttplus") {
			wdttArgs, initial, ok := wdttPlusProcess(config)
			if !ok {
				if session.release != nil {
					session.release()
				}
				return errors.New("invalid WDTT Plus process configuration")
			}
			args = wdttArgs
			initialCommand = initial
			var safe map[string]any
			if json.Unmarshal([]byte(config), &safe) != nil {
				if session.release != nil {
					session.release()
				}
				return errors.New("invalid WDTT Plus process configuration")
			}
			delete(safe, "start")
			bytes, marshalErr := json.Marshal(safe)
			if marshalErr != nil {
				if session.release != nil {
					session.release()
				}
				return errors.New("invalid WDTT Plus process configuration")
			}
			config = string(bytes)
			// The native client receives passwords and hashes only through its private stdin pipe.
		}
		confPath, folder, e := process.CreateExtraConfig(config)
		if e != nil {
			if session.release != nil {
				session.release()
			}
			return errors.New("failed to create private extra process configuration")
		}
		for idx, arg := range args {
			if strings.Contains(arg, "%s") {
				args[idx] = fmt.Sprintf(arg, confPath)
				break
			}
		}
		session.child = process.NewProcess(spec.GetPath(), args, spec.GetNoOut())
		if session.transport && session.tag != "proxy" {
			session.child.SetRoutedTransport()
		}
		session.child.SetCleanupPath(folder)
		if session.transport {
			session.child.EnableStdinShutdown("STOP")
		}
		if initialCommand != "" {
			session.child.EnableStdinShutdown("STOP")
			session.child.SetStdinInitialCommand(initialCommand)
		}
		extraSessions = append(extraSessions, session)
		if e := session.child.Start(); e != nil {
			return e
		}
	}
	return nil
}

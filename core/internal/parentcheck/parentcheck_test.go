package parentcheck

import "testing"

func TestIsExpectedWindowsParent(t *testing.T) {
	for _, name := range []string{"qThrone.exe", "OnlineSetiVPN.exe", "onlinesetivpn.EXE"} {
		if !isExpectedWindowsParent(name) {
			t.Errorf("expected %q to be accepted", name)
		}
	}
	for _, name := range []string{"other.exe", "OnlineSetiVPN.exe.bak", "qThrone"} {
		if isExpectedWindowsParent(name) {
			t.Errorf("expected %q to be rejected", name)
		}
	}
}

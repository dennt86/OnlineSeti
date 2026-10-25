package mobile

import (
	"encoding/json"
	"github.com/sagernet/sing-box/adapter"
	"testing"
)

type authTestEndpoint struct {
	adapter.OpenConnectEndpoint
	challenge *adapter.OpenConnectAuthChallenge
	response  *adapter.OpenConnectAuthResponse
	cancelled bool
}

func (e *authTestEndpoint) Tag() string { return "proxy" }
func (e *authTestEndpoint) OpenConnectStatus() adapter.OpenConnectStatus {
	return adapter.OpenConnectStatus{AuthChallenge: e.challenge}
}
func (e *authTestEndpoint) CompleteAuthChallenge(id string, response adapter.OpenConnectAuthResponse) error {
	e.response = &response
	return nil
}
func (e *authTestEndpoint) CancelAuthChallenge(id string) error { e.cancelled = true; return nil }

type authTestManager struct {
	adapter.EndpointManager
	endpoint *authTestEndpoint
}

func (m authTestManager) Endpoints() []adapter.Endpoint           { return []adapter.Endpoint{m.endpoint} }
func (m authTestManager) Get(tag string) (adapter.Endpoint, bool) { return m.endpoint, tag == "proxy" }

func TestOpenConnectAuthContinuation(t *testing.T) {
	endpoint := &authTestEndpoint{challenge: &adapter.OpenConnectAuthChallenge{
		ID: "request-1", Form: &adapter.OpenConnectAuthForm{Fields: []adapter.OpenConnectAuthFormField{
			{SubmissionKey: "form:group:0", Kind: "select", Options: []adapter.OpenConnectAuthFormChoice{{Value: "VPN", Label: "VPN"}}},
			{SubmissionKey: "form:password:1", Kind: "password"},
		}},
	}}
	manager := authTestManager{endpoint: endpoint}
	data, err := pendingOpenConnectAuth(manager)
	if err != nil {
		t.Fatal(err)
	}
	var request struct {
		Tag       string
		Challenge *adapter.OpenConnectAuthChallenge
	}
	if err = json.Unmarshal([]byte(data), &request); err != nil {
		t.Fatal(err)
	}
	if request.Tag != "proxy" || request.Challenge.ID != "request-1" || len(request.Challenge.Form.Fields) != 2 {
		t.Fatal("lost authentication continuation")
	}
	if err = replyOpenConnectAuth(manager, "proxy", "request-1", `{"form:group:0":"VPN","form:password:1":"123456"}`, false); err != nil {
		t.Fatal(err)
	}
	if endpoint.response.Form.Values["form:password:1"] != "123456" || endpoint.response.Form.Values["form:group:0"] != "VPN" {
		t.Fatal("lost form answers")
	}
}

func TestOpenConnectRejectsExpiredAndBrowserAnswers(t *testing.T) {
	endpoint := &authTestEndpoint{challenge: &adapter.OpenConnectAuthChallenge{ID: "new", Form: &adapter.OpenConnectAuthForm{}}}
	manager := authTestManager{endpoint: endpoint}
	for _, tag := range []string{"missing", "proxy"} {
		if err := replyOpenConnectAuth(manager, tag, "old", `{}`, false); err == nil {
			t.Fatal("accepted stale challenge")
		}
	}
	if endpoint.response != nil {
		t.Fatal("submitted stale answers")
	}
	if err := replyOpenConnectAuth(manager, "proxy", "new", `{"password":123}`, false); err == nil {
		t.Fatal("accepted invalid answers")
	}
	endpoint.challenge.Browser = &adapter.OpenConnectBrowserRequest{URL: "https://example.invalid"}
	if err := replyOpenConnectAuth(manager, "proxy", "new", `{}`, false); err == nil {
		t.Fatal("submitted a form to browser authentication")
	}
	if err := replyOpenConnectAuth(manager, "proxy", "new", "", true); err != nil || !endpoint.cancelled {
		t.Fatal("cancel failed", err)
	}
	endpoint.challenge = nil
	if data, err := pendingOpenConnectAuth(manager); err != nil || data != "" {
		t.Fatal("completed challenge remained visible", data, err)
	}
}

package mobile

import (
	"encoding/json"

	"github.com/sagernet/sing-box/adapter"
	E "github.com/sagernet/sing/common/exceptions"
)

// OpenConnectAuth exposes interactive forms without changing Android's VPN service state.
// Each submission is addressed to an endpoint and the core's unique challenge ID.
func (i *Instance) OpenConnectAuth() (string, error) {
	if !i.running() {
		return "", nil
	}
	return pendingOpenConnectAuth(i.handle.Endpoint())
}

func pendingOpenConnectAuth(manager adapter.EndpointManager) (string, error) {
	for _, endpoint := range manager.Endpoints() {
		client, ok := endpoint.(adapter.OpenConnectEndpoint)
		if !ok {
			continue
		}
		challenge := client.OpenConnectStatus().AuthChallenge
		if challenge == nil {
			continue
		}
		data, err := json.Marshal(struct {
			Tag       string
			Challenge *adapter.OpenConnectAuthChallenge
		}{endpoint.Tag(), challenge})
		return string(data), err
	}
	return "", nil
}

func (i *Instance) ReplyOpenConnectAuth(tag, challengeID, valuesJSON string, cancel bool) error {
	if !i.running() {
		return E.New("OpenConnect instance is no longer running")
	}
	return replyOpenConnectAuth(i.handle.Endpoint(), tag, challengeID, valuesJSON, cancel)
}

func replyOpenConnectAuth(manager adapter.EndpointManager, tag, challengeID, valuesJSON string, cancel bool) error {
	endpoint, loaded := manager.Get(tag)
	if !loaded {
		return E.New("OpenConnect endpoint no longer exists")
	}
	client, ok := endpoint.(adapter.OpenConnectEndpoint)
	if !ok {
		return E.New("endpoint is not OpenConnect")
	}
	challenge := client.OpenConnectStatus().AuthChallenge
	if challenge == nil || challenge.ID != challengeID {
		return E.New("OpenConnect authentication request has expired")
	}
	if cancel {
		return client.CancelAuthChallenge(challengeID)
	}
	if challenge.Form == nil || challenge.Browser != nil {
		return E.New("this authentication request requires a browser")
	}
	var values map[string]string
	if err := json.Unmarshal([]byte(valuesJSON), &values); err != nil {
		return err
	}
	return client.CompleteAuthChallenge(challengeID, adapter.OpenConnectAuthResponse{
		Form: &adapter.OpenConnectAuthFormResponse{Values: values},
	})
}

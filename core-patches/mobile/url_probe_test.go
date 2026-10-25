package mobile

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

type scopedURLHandler struct {
	results chan string
	done    chan struct{}
}

func (h *scopedURLHandler) OnResult(_ string, _ int32, failure string) { h.results <- failure }
func (h *scopedURLHandler) OnVPNStatus(string, bool, string, string)   {}
func (h *scopedURLHandler) OnDone()                                    { close(h.done) }

func TestScopedURLCancellationDoesNotAbortNeighbour(t *testing.T) {
	entered := make(chan struct{})
	slow := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-entered:
		default:
			close(entered)
		}
		<-r.Context().Done()
	}))
	defer slow.Close()
	fast := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(204) }))
	defer fast.Close()
	start := func(url string) (*URLTestTask, *scopedURLHandler) {
		h := &scopedURLHandler{results: make(chan string, 4), done: make(chan struct{})}
		r := &TestRequest{CoreConfig: `{"log":{"disabled":true},"outbounds":[{"type":"direct","tag":"proxy"}]}`,
			URL: url, TimeoutMs: 30000, MaxConcurrency: 1}
		r.AddOutboundTag("proxy") // Same tags must stay isolated too.
		task, err := StartScopedURLTest(nil, nil, r, h)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(task.Cancel)
		return task, h
	}
	task, first := start(slow.URL)
	select {
	case <-entered:
	case failure := <-first.results:
		t.Fatalf("slow request failed before entering handler: %s", failure)
	case <-time.After(5 * time.Second):
		t.Fatal("slow request did not start")
	}
	_, second := start(fast.URL)
	select {
	case failure := <-second.results:
		if failure != "" {
			t.Fatal(failure)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("independent probe blocked")
	}
	task.Cancel()
	select {
	case <-first.done:
	case <-time.After(5 * time.Second):
		t.Fatal("cancel did not release probe")
	}
	select {
	case <-second.done:
	case <-time.After(5 * time.Second):
		t.Fatal("neighbour did not finish")
	}
	// A cancelled probe must not leave the global context cancelled for the next test.
	_, third := start(fast.URL)
	select {
	case failure := <-third.results:
		if failure != "" {
			t.Fatal(failure)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("repeat probe blocked")
	}
	select {
	case <-third.done:
	case <-time.After(5 * time.Second):
		t.Fatal("repeat did not finish")
	}
}

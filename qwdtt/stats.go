package main

import (
	"log"
	"sync/atomic"
	"time"
)

type Stats struct {
	TotalBytesUp      atomic.Int64
	TotalBytesDown    atomic.Int64
	ActiveConnections atomic.Int32
	TunPacketsUp      atomic.Int64
	TunPacketsSent    atomic.Int64
	TunPacketsDown    atomic.Int64
	TunPacketsDropped atomic.Int64
	ReturnPacketsDrop atomic.Int64
	TunReadErrors     atomic.Int64
	TunWriteErrors    atomic.Int64
	KeepalivesSent    atomic.Int64
	KeepalivesRecv    atomic.Int64
	TransportRead     atomic.Int64
	TransportWrite    atomic.Int64
}

func NewStats() *Stats {
	return &Stats{}
}

func (s *Stats) RunLoop(shutdown <-chan struct{}) {
	ticker := time.NewTicker(3 * time.Second)
	defer ticker.Stop()
	var prevUp, prevDown int64

	for {
		select {
		case <-shutdown:
			return
		case <-ticker.C:
			active := s.ActiveConnections.Load()
			up := s.TotalBytesUp.Load()
			down := s.TotalBytesDown.Load()
			upDelta, downDelta := up-prevUp, down-prevDown
			prevUp, prevDown = up, down
			totalMB := float64(up+down) / (1024.0 * 1024.0)
			upMB := float64(up) / (1024.0 * 1024.0)
			downMB := float64(down) / (1024.0 * 1024.0)

			log.Printf("[СТАТИСТИКА] Активных: %d | Трафик: %.2f МБ | ↓%.2f МБ / ↑%.2f МБ", active, totalMB, downMB, upMB)
			log.Printf("[WDTT-DIAG] interval=3s up_bytes=%d down_bytes=%d tun_read=%d tun_queued=%d tun_written=%d tun_queue_drop=%d tun_io_errors=%d/%d relay_rx=%d relay_tx=%d keepalive_queued=%d keepalive_rx=%d return_queue_drop=%d",
				upDelta, downDelta, s.TunPacketsUp.Load(), s.TunPacketsSent.Load(), s.TunPacketsDown.Load(), s.TunPacketsDropped.Load(),
				s.TunReadErrors.Load(), s.TunWriteErrors.Load(), s.TransportRead.Load(), s.TransportWrite.Load(), s.KeepalivesSent.Load(), s.KeepalivesRecv.Load(), s.ReturnPacketsDrop.Load())
		}
	}
}

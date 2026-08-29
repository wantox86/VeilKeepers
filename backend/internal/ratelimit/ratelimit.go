// Package ratelimit implements a per-key token-bucket limiter used to
// throttle login attempts by client IP. It is fully synchronous: no
// background goroutines are started.
package ratelimit

import (
	"sync"
	"time"
)

// sweepThreshold is the bucket count above which Allow performs an
// opportunistic cleanup of fully refilled buckets.
const sweepThreshold = 8192

// bucket tracks the token state for one key.
type bucket struct {
	tokens float64
	last   time.Time
}

// Limiter is a token-bucket rate limiter keyed by an arbitrary string
// (typically a client IP). It is safe for concurrent use.
type Limiter struct {
	mu      sync.Mutex
	buckets map[string]*bucket

	capacity        float64
	refillPerMinute float64

	// NowFunc supplies the current time; inject a fake clock in tests.
	NowFunc func() time.Time
}

// New returns a Limiter with the given bucket capacity and refill rate
// (tokens per minute). When now is nil, time.Now is used.
func New(capacity int, refillPerMinute float64, now func() time.Time) *Limiter {
	if now == nil {
		now = time.Now
	}
	return &Limiter{
		buckets:         make(map[string]*bucket),
		capacity:        float64(capacity),
		refillPerMinute: refillPerMinute,
		NowFunc:         now,
	}
}

// Allow consumes one token for key and reports whether the request is
// permitted. Tokens are refilled lazily based on elapsed time; when the
// map grows past sweepThreshold entries, fully refilled buckets are
// evicted first.
func (l *Limiter) Allow(key string) bool {
	now := l.NowFunc()

	l.mu.Lock()
	defer l.mu.Unlock()

	if len(l.buckets) > sweepThreshold {
		l.sweep(now)
	}

	b, ok := l.buckets[key]
	if !ok {
		// A fresh bucket starts full minus the token consumed now.
		l.buckets[key] = &bucket{tokens: l.capacity - 1, last: now}
		return true
	}

	l.refill(b, now)
	if b.tokens >= 1 {
		b.tokens--
		return true
	}
	return false
}

// refill adds tokens earned since b.last, capped at the bucket capacity.
func (l *Limiter) refill(b *bucket, now time.Time) {
	if l.refillPerMinute <= 0 {
		b.last = now
		return
	}
	elapsed := now.Sub(b.last)
	if elapsed > 0 {
		b.tokens += elapsed.Minutes() * l.refillPerMinute
		if b.tokens > l.capacity {
			b.tokens = l.capacity
		}
	}
	b.last = now
}

// sweep removes buckets that have refilled to full capacity; deleting
// them is observationally identical to keeping them and bounds memory.
func (l *Limiter) sweep(now time.Time) {
	if l.refillPerMinute <= 0 {
		return
	}
	fullRefill := time.Duration(l.capacity / l.refillPerMinute * float64(time.Minute))
	for k, b := range l.buckets {
		if now.Sub(b.last) >= fullRefill {
			delete(l.buckets, k)
		}
	}
}

// Len returns the number of tracked buckets. Intended for tests and
// diagnostics.
func (l *Limiter) Len() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.buckets)
}

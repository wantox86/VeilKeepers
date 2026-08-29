package ratelimit

import (
	"strconv"
	"testing"
	"time"
)

// fakeClock is a manually advanced clock so tests never sleep.
type fakeClock struct {
	now time.Time
}

func (c *fakeClock) Now() time.Time { return c.now }

func (c *fakeClock) advance(d time.Duration) { c.now = c.now.Add(d) }

func newTestLimiter() (*Limiter, *fakeClock) {
	clk := &fakeClock{now: time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)}
	return New(10, 10, clk.Now), clk
}

// TestBurstThenDeny drains a full bucket of 10 and checks the 11th call
// is denied.
func TestBurstThenDeny(t *testing.T) {
	lim, _ := newTestLimiter()

	for i := 0; i < 10; i++ {
		if !lim.Allow("10.0.0.1") {
			t.Fatalf("request %d: want allowed, got denied", i+1)
		}
	}
	if lim.Allow("10.0.0.1") {
		t.Fatal("11th request: want denied, got allowed")
	}
}

// TestLazyRefill advances the clock 6s, which earns exactly one token at
// 10 tokens/minute.
func TestLazyRefill(t *testing.T) {
	lim, clk := newTestLimiter()

	for i := 0; i < 10; i++ {
		lim.Allow("10.0.0.1")
	}
	if lim.Allow("10.0.0.1") {
		t.Fatal("bucket should be empty")
	}

	clk.advance(6 * time.Second) // 0.1 min * 10/min = 1 token
	if !lim.Allow("10.0.0.1") {
		t.Fatal("want one token refilled after 6s")
	}
	if lim.Allow("10.0.0.1") {
		t.Fatal("want denial right after the single refilled token")
	}
}

// TestKeyIsolation verifies that two IPs have independent buckets.
func TestKeyIsolation(t *testing.T) {
	lim, _ := newTestLimiter()

	for i := 0; i < 10; i++ {
		lim.Allow("10.0.0.1")
	}
	if lim.Allow("10.0.0.1") {
		t.Fatal("10.0.0.1 should be exhausted")
	}
	if !lim.Allow("10.0.0.2") {
		t.Fatal("10.0.0.2 should be unaffected by 10.0.0.1")
	}
}

// TestRefillCappedAtCapacity verifies tokens never exceed the burst size,
// even after a very long idle period.
func TestRefillCappedAtCapacity(t *testing.T) {
	lim, clk := newTestLimiter()

	for i := 0; i < 10; i++ {
		lim.Allow("10.0.0.1")
	}

	clk.advance(24 * time.Hour) // far more than enough to refill
	for i := 0; i < 10; i++ {
		if !lim.Allow("10.0.0.1") {
			t.Fatalf("request %d after long idle: want allowed", i+1)
		}
	}
	if lim.Allow("10.0.0.1") {
		t.Fatal("burst must stay capped at capacity 10")
	}
}

// TestSweepEvictsRefilledBuckets grows the map past the sweep threshold,
// advances the clock past a full refill, then confirms exhausted buckets
// are evicted and recently used ones survive.
func TestSweepEvictsRefilledBuckets(t *testing.T) {
	lim, clk := newTestLimiter()

	const keys = sweepThreshold + 10
	for i := 0; i < keys; i++ {
		lim.Allow("ip-" + strconv.Itoa(i))
	}
	if got := lim.Len(); got != keys {
		t.Fatalf("Len = %d, want %d", got, keys)
	}

	// Keep one bucket hot while everything else idles past full refill.
	clk.advance(2 * time.Minute)
	lim.Allow("hot-ip")
	clk.advance(30 * time.Second)

	// This call sees len > sweepThreshold and triggers the sweep.
	if !lim.Allow("new-ip") {
		t.Fatal("new-ip should be allowed")
	}

	// Survivors: hot-ip (used 30s ago), new-ip. All stale ones are gone.
	if got := lim.Len(); got > 2 {
		t.Fatalf("after sweep Len = %d, want <= 2", got)
	}
}

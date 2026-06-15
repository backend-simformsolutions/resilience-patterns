# Resilience Patterns — Detailed Guide

## Table of Contents

1. [What Are Resilience Patterns?](#what-are-resilience-patterns)
2. [Pattern 1: Retry](#pattern-1-retry)
3. [Pattern 2: Circuit Breaker](#pattern-2-circuit-breaker)
4. [Pattern 3: Timeout (TimeLimiter)](#pattern-3-timeout-timelimiter)
5. [How Patterns Work Together](#how-patterns-work-together)
6. [Implementation Details in This POC](#implementation-details-in-this-poc)
7. [How to Test Each Pattern](#how-to-test-each-pattern)
8. [Configuration Reference](#configuration-reference)

---

## What Are Resilience Patterns?

In distributed systems, services communicate over the network — and networks are inherently unreliable. Services can be slow, temporarily down, or overloaded. **Resilience patterns** are design strategies that help applications handle these failures gracefully, ensuring the system remains operational even when individual components fail.

Without resilience patterns:
- A single slow service can cascade into a system-wide outage.
- Transient errors cause immediate user-facing failures.
- Failing services get hammered with retries, making recovery harder.

With resilience patterns:
- Transient failures are handled transparently through retries.
- Persistent failures are detected early and calls are short-circuited.
- Slow services are timed out to prevent resource exhaustion.

---

## Pattern 1: Retry

### What It Does

The **Retry** pattern automatically re-attempts a failed operation a configured number of times before giving up. It is designed to handle **transient failures** — temporary issues that resolve themselves after a short time (e.g., a network blip, a momentary service restart).

### When to Use

| Scenario | Use Retry? |
|---|---|
| Network timeout / connection reset | Yes |
| Service temporarily unavailable (503) | Yes |
| Database deadlock | Yes (with backoff) |
| Invalid input (400 Bad Request) | **No** — retrying won't fix bad data |
| Authentication failure (401/403) | **No** — credentials won't change |
| Business rule violation | **No** — use validation instead |

### How It Works

```
Client Request
    │
    ▼
┌──────────────┐    Failure    ┌──────────────┐
│   Attempt 1  │ ────────────► │   Wait (2s)  │
└──────────────┘               └──────┬───────┘
                                      │
                                      ▼
                               ┌──────────────┐    Failure    ┌──────────────┐
                               │   Attempt 2  │ ────────────► │   Wait (2s)  │
                               └──────────────┘               └──────┬───────┘
                                                                     │
                                                                     ▼
                                                              ┌──────────────┐
                                                              │   Attempt 3  │ ──► Success OR Fallback
                                                              └──────────────┘
```

### Key Configuration (application.yaml)

```yaml
resilience4j:
  retry:
    instances:
      paymentService:
        max-attempts: 3              # Total attempts (including the initial one)
        wait-duration: 2s            # Fixed wait between retries
        retry-exceptions:            # Only retry on these exceptions
          - ServiceUnavailableException
          - IOException
        ignore-exceptions:           # Never retry on these
          - BusinessValidationException
```

### Implementation in This POC

- **Service:** `PaymentService.processPayment()`
- **Annotation:** `@Retry(name = "paymentService", fallbackMethod = "paymentFallback")`
- **Behavior:** When the external payment gateway fails (throws `ServiceUnavailableException`), the retry mechanism attempts the call up to 3 times with a 2-second wait between each attempt. If all retries are exhausted, the fallback returns a `PENDING` payment response.

---

## Pattern 2: Circuit Breaker

### What It Does

The **Circuit Breaker** pattern monitors the failure rate of calls to a downstream service. When the failure rate exceeds a threshold, the circuit "opens" and **stops sending requests** to the failing service for a configured duration. This prevents:

- Cascading failures across the system.
- Wasting resources on calls that are likely to fail.
- Overloading a service that is trying to recover.

### State Machine

```
                    failure rate < threshold
                 ┌───────────────────────────┐
                 │                           │
                 ▼                           │
          ┌─────────────┐            ┌──────────────┐
          │   CLOSED    │            │   HALF_OPEN   │
          │ (Normal)    │            │ (Testing)     │
          └──────┬──────┘            └───────┬──────┘
                 │                           │
                 │ failure rate              │ failure rate
                 │ >= threshold              │ >= threshold
                 │                           │
                 ▼                           ▼
          ┌──────────────┐           ┌──────────────┐
          │    OPEN      │──────────►│   HALF_OPEN   │
          │ (Rejecting)  │  timeout  │ (Testing)     │
          └──────────────┘  expires  └──────────────┘
```

**States explained:**

| State | Behavior |
|---|---|
| **CLOSED** | Normal operation. All calls pass through. Failures are recorded in a sliding window. |
| **OPEN** | All calls are immediately rejected with `CallNotPermittedException`. No requests reach the downstream service. |
| **HALF_OPEN** | A limited number of trial calls are permitted. If they succeed, the circuit transitions back to CLOSED. If they fail, it goes back to OPEN. |

### Key Configuration (application.yaml)

```yaml
resilience4j:
  circuitbreaker:
    instances:
      paymentService:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 5              # Track last 5 calls
        minimum-number-of-calls: 3          # Need at least 3 calls before evaluating
        failure-rate-threshold: 50          # Open if >= 50% of calls fail
        wait-duration-in-open-state: 10s    # Stay OPEN for 10 seconds
        permitted-number-of-calls-in-half-open-state: 2  # Allow 2 trial calls
        automatic-transition-from-open-to-half-open-enabled: true
```

### Implementation in This POC

- **Services:** `PaymentService` and `InventoryService`
- **Annotation:** `@CircuitBreaker(name = "paymentService", fallbackMethod = "paymentFallback")`
- **Behavior:** When the circuit is CLOSED, calls proceed normally. When too many calls fail (50% in a window of 5), the circuit opens. While OPEN, all calls immediately invoke the fallback. After 10 seconds, it transitions to HALF_OPEN and allows 2 trial calls.

---

## Pattern 3: Timeout (TimeLimiter)

### What It Does

The **Timeout** (TimeLimiter) pattern sets a maximum duration for a call. If the downstream service does not respond within the allowed time, the call is cancelled and a fallback is invoked. This prevents:

- Thread pool exhaustion from blocked threads waiting indefinitely.
- Resource leaks from connections that never close.
- Slowdown cascading to upstream services.

### How It Works

```
Client Request
    │
    ▼
┌──────────────────────────────────────────────────────┐
│                   TimeLimiter (2s)                    │
│                                                      │
│   ┌──────────────────────┐                           │
│   │  CompletableFuture   │                           │
│   │  async call to       │   ── responds in 1s ───► │ ✅ Return result
│   │  inventory service   │                           │
│   │                      │   ── responds in 5s ───► │ ⏰ TIMEOUT → Fallback
│   └──────────────────────┘                           │
│                                                      │
└──────────────────────────────────────────────────────┘
```

### Key Configuration (application.yaml)

```yaml
resilience4j:
  timelimiter:
    instances:
      inventoryService:
        timeout-duration: 2s              # Max wait time
        cancel-running-future: true       # Cancel the thread if timeout is reached
```

### Implementation in This POC

- **Service:** `InventoryService.checkInventory()`
- **Annotation:** `@TimeLimiter(name = "inventoryService", fallbackMethod = "inventoryFallback")`
- **Requirement:** The method must return `CompletableFuture<T>` so the TimeLimiter can manage the async execution.
- **Behavior:** If the inventory service responds within 2 seconds, the result is returned. If it takes longer, the future is cancelled and the fallback returns an `UNKNOWN` inventory status.

---

## How Patterns Work Together

In real-world applications, these patterns are often **combined** for maximum resilience. In this POC:

### Payment Service: Retry + Circuit Breaker

```
Incoming Call
    │
    ▼
┌────────────────────┐
│  Circuit Breaker   │ ── OPEN? ─── ► Fallback (immediate)
│                    │
│  CLOSED/HALF_OPEN  │
└────────┬───────────┘
         │
         ▼
┌────────────────────┐
│      Retry         │ ── Attempt 1 → Fail → Wait → Attempt 2 → Fail → Wait → Attempt 3 → Success/Fallback
│                    │
└────────────────────┘
```

Each failed attempt is also recorded by the circuit breaker. If enough calls fail, the circuit opens and subsequent calls are short-circuited without any retries.

### Inventory Service: Timeout + Circuit Breaker

```
Incoming Call
    │
    ▼
┌────────────────────┐
│  Circuit Breaker   │ ── OPEN? ─── ► Fallback (immediate)
│                    │
│  CLOSED/HALF_OPEN  │
└────────┬───────────┘
         │
         ▼
┌────────────────────┐
│    TimeLimiter     │ ── Responds in time? ── ► Return result
│    (2 seconds)     │ ── Too slow?       ── ► Cancel + Fallback
└────────────────────┘
```

Timeouts are also recorded as failures by the circuit breaker, so a consistently slow service will eventually trigger the circuit to open.

---

## Implementation Details in This POC

### Architecture Overview

```
┌───────────────────────────────────────────────────────────────┐
│                        REST Layer                             │
│  OrderController              SimulationController            │
└──────────┬────────────────────────────┬───────────────────────┘
           │                            │
           ▼                            ▼
┌──────────────────────┐   ┌────────────────────────────────────┐
│    OrderService      │   │  Simulation Controls               │
│  (Orchestrator)      │   │  (Toggle failures/delays/reset)    │
└────┬────────────┬────┘   └────────────────────────────────────┘
     │            │
     ▼            ▼
┌──────────┐  ┌───────────────┐
│ Payment  │  │  Inventory    │
│ Service  │  │  Service      │
│ [Retry]  │  │ [Timeout]     │
│ [CB]     │  │ [CB]          │
└────┬─────┘  └───────┬───────┘
     │                │
     ▼                ▼
┌──────────┐  ┌───────────────┐
│ External │  │  External     │
│ Payment  │  │  Inventory    │
│ Client   │  │  Client       │
│(Simulated│  │ (Simulated)   │
└──────────┘  └───────────────┘
```

### Key Classes

| Class | Responsibility |
|---|---|
| `OrderController` | REST endpoint for placing orders |
| `SimulationController` | REST endpoints to toggle failure/delay modes |
| `OrderService` | Orchestrates the order workflow |
| `PaymentService` | Processes payments with Retry + Circuit Breaker |
| `InventoryService` | Checks inventory with Timeout + Circuit Breaker |
| `ExternalPaymentClient` | Simulates external payment gateway (configurable failures) |
| `ExternalInventoryClient` | Simulates external inventory system (configurable delays/failures) |
| `GlobalExceptionHandler` | Maps resilience exceptions to proper HTTP responses |

---

## How to Test Each Pattern

### 1. Test Retry Pattern

```bash
# Reset everything
curl -X POST http://localhost:8080/api/v1/simulation/reset

# Configure payment to fail 2 times, then succeed (retry max = 3)
curl -X POST "http://localhost:8080/api/v1/simulation/payment/failures?count=2"

# Place an order — should succeed after 2 retries
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{"productId":"PROD-001","quantity":2,"amount":99.99,"customerId":"CUST-001"}'
# Expected: paymentStatus = "SUCCESS" (retries handled internally)
```

### 2. Test Retry Exhaustion (Fallback)

```bash
# Configure payment to fail 10 times (exceeds max-attempts of 3)
curl -X POST "http://localhost:8080/api/v1/simulation/payment/failures?count=10"

# Place an order — retry will be exhausted, fallback should kick in
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{"productId":"PROD-002","quantity":1,"amount":50.00,"customerId":"CUST-002"}'
# Expected: paymentStatus = "PENDING" (fallback response)
```

### 3. Test Timeout Pattern

```bash
# Reset
curl -X POST http://localhost:8080/api/v1/simulation/reset

# Configure inventory to simulate 5-second delay (timeout is 2s)
curl -X POST "http://localhost:8080/api/v1/simulation/inventory/delay?enabled=true&millis=5000"

# Place an order — inventory check will timeout
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{"productId":"PROD-003","quantity":3,"amount":75.00,"customerId":"CUST-003"}'
# Expected: inventoryStatus = "UNKNOWN" (timeout fallback)
```

### 4. Test Circuit Breaker Pattern

```bash
# Reset
curl -X POST http://localhost:8080/api/v1/simulation/reset

# Configure inventory to always fail
curl -X POST "http://localhost:8080/api/v1/simulation/inventory/failure?enabled=true"

# Send multiple requests to trip the circuit breaker
for i in {1..10}; do
  curl -s -X POST http://localhost:8080/api/v1/orders \
    -H "Content-Type: application/json" \
    -d '{"productId":"PROD-004","quantity":1,"amount":25.00,"customerId":"CUST-004"}'
  echo ""
done

# Check circuit breaker state
curl http://localhost:8080/api/v1/simulation/status
# Expected: inventoryService circuit breaker state = OPEN
```

### 5. Check Resilience Status

```bash
# View current state of all circuit breakers
curl http://localhost:8080/api/v1/simulation/status

# Also available via Actuator
curl http://localhost:8080/actuator/health
```

---

## Configuration Reference

### Retry Properties

| Property | Description | Default in POC |
|---|---|---|
| `max-attempts` | Total number of attempts (including initial) | 3 |
| `wait-duration` | Fixed wait time between retries | 2s |
| `enable-exponential-backoff` | Whether to use exponential backoff | false (payment), true (inventory) |
| `exponential-backoff-multiplier` | Multiplier for exponential backoff | 2 |
| `retry-exceptions` | Exceptions that should trigger a retry | `ServiceUnavailableException`, `IOException` |
| `ignore-exceptions` | Exceptions that should NOT be retried | `BusinessValidationException` |

### Circuit Breaker Properties

| Property | Description | Default in POC |
|---|---|---|
| `sliding-window-type` | COUNT_BASED or TIME_BASED | COUNT_BASED |
| `sliding-window-size` | Number of calls in the sliding window | 5 (payment), 10 (inventory) |
| `minimum-number-of-calls` | Minimum calls before evaluating failure rate | 3 (payment), 5 (inventory) |
| `failure-rate-threshold` | Percentage of failures to trigger OPEN | 50% (payment), 60% (inventory) |
| `wait-duration-in-open-state` | How long to stay in OPEN before transitioning to HALF_OPEN | 10s (payment), 15s (inventory) |
| `permitted-number-of-calls-in-half-open-state` | Trial calls allowed in HALF_OPEN | 2 (payment), 3 (inventory) |

### TimeLimiter Properties

| Property | Description | Default in POC |
|---|---|---|
| `timeout-duration` | Maximum allowed response time | 3s (payment), 2s (inventory) |
| `cancel-running-future` | Whether to cancel the task on timeout | true |

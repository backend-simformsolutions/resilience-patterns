# Resilience Patterns POC — Spring Boot + Resilience4j

A production-style Proof of Concept demonstrating **Retry**, **Circuit Breaker**, and **Timeout** resilience patterns in a Spring Boot application using [Resilience4j](https://resilience4j.readme.io/).

---

## Table of Contents

- [What Are Resilience Patterns?](#what-are-resilience-patterns)
- [What Scenarios Do They Solve?](#what-scenarios-do-they-solve)
- [What This POC Covers](#what-this-poc-covers)
- [How the Implementation Works](#how-the-implementation-works)
- [Tech Stack & Libraries](#tech-stack--libraries)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [API Reference](#api-reference)
- [Testing Guide](#testing-guide)
- [Real-World Use Cases](#real-world-use-cases)
- [Further Reading](#further-reading)

---

## What Are Resilience Patterns?

Resilience patterns are software design strategies that help distributed systems **tolerate and recover from failures** in dependent services. In microservice architectures, services communicate over networks — and networks fail. These patterns ensure that a failure in one service does not cascade into a system-wide outage.

The three core patterns implemented in this POC are:

| Pattern | Purpose |
|---|---|
| **Retry** | Automatically re-attempts a failed call to handle transient errors |
| **Circuit Breaker** | Detects prolonged failures and stops calling a failing service to allow it to recover |
| **Timeout (TimeLimiter)** | Bounds the maximum wait time for a response, preventing thread/resource exhaustion |

> For a deep dive into each pattern, see [PATTERNS.md](PATTERNS.md).

---

## What Scenarios Do They Solve?

| Problem | Without Resilience | With Resilience |
|---|---|---|
| **Transient network error** | Immediate failure shown to user | Retry handles it transparently |
| **Service is down for 30 seconds** | Every call fails for 30 seconds | Circuit breaker stops calls after a few failures, triggers fallback |
| **Slow service (10s response time)** | Caller thread blocked for 10s, resource exhaustion | Timeout cancels after 2s, returns fallback |
| **Cascading failures** | Service A waits on B, which waits on C — all go down | Circuit breaker isolates the failure |
| **Thundering herd on recovery** | Service comes back and gets flooded | Circuit breaker's HALF_OPEN state controls recovery traffic |

---

## What This POC Covers

This POC simulates a realistic **Order Processing System** with two external dependencies:

1. **Payment Gateway** (simulated) — protected by **Retry** + **Circuit Breaker**
2. **Inventory/Warehouse Service** (simulated) — protected by **Timeout** + **Circuit Breaker**

### Features Demonstrated

- Retry with fixed delay and exponential backoff
- Circuit breaker with count-based sliding window
- Timeout (TimeLimiter) with CompletableFuture
- Fallback methods for graceful degradation
- Configurable failure/delay simulation via REST endpoints
- Circuit breaker state observation via custom endpoint and Actuator
- Global exception handling for resilience-specific exceptions
- Integration tests for all three patterns
- Proper separation of concerns (Controller → Service → Client)

---

## How the Implementation Works

### Architecture

```
┌─────────────────────────────────────────────────────────┐
│                     REST Controllers                     │
│  OrderController             SimulationController        │
└──────────┬───────────────────────────┬──────────────────┘
           │                           │
           ▼                           ▼
┌──────────────────┐       ┌───────────────────────────┐
│   OrderService   │       │  Simulation Controls      │
│  (Orchestrator)  │       │  (Toggle failures/delays) │
└───┬──────────┬───┘       └───────────────────────────┘
    │          │
    ▼          ▼
┌────────┐  ┌──────────────┐
│Payment │  │  Inventory   │
│Service │  │  Service     │
│[Retry] │  │ [Timeout]    │
│[CB]    │  │ [CB]         │
└───┬────┘  └──────┬───────┘
    │              │
    ▼              ▼
┌────────┐  ┌──────────────┐
│External│  │  External    │
│Payment │  │  Inventory   │
│Client  │  │  Client      │
└────────┘  └──────────────┘
```

### Request Flow

1. Client sends `POST /api/v1/orders` with an order request.
2. `OrderController` delegates to `OrderService`.
3. `OrderService` validates input, then calls:
   - **InventoryService** (protected by `@TimeLimiter` + `@CircuitBreaker`)
   - **PaymentService** (protected by `@Retry` + `@CircuitBreaker`)
4. Each service calls a simulated external client.
5. If calls fail, resilience patterns handle the failure (retry, timeout, or circuit break).
6. Fallback methods return safe default responses.
7. `OrderService` aggregates results into the final `OrderResponse`.

### Simulation Layer

The external clients (`ExternalPaymentClient`, `ExternalInventoryClient`) have configurable behaviors that you can toggle via the `SimulationController`:

- **Payment**: Configure how many calls fail before success.
- **Inventory**: Toggle delay (for timeout testing) or failure mode (for circuit breaker testing).

This allows you to observe resilience patterns in action without needing actual external services.

---

## Tech Stack & Libraries

| Component | Technology | Version |
|---|---|---|
| Framework | Spring Boot | 4.0.6 |
| Resilience Library | Resilience4j | 2.3.0 |
| Language | Java | 17 |
| Build Tool | Maven | 3.x |
| AOP Support | Spring AOP | (via spring-boot-starter-aop) |
| Monitoring | Spring Boot Actuator | (via spring-boot-starter-actuator) |
| Boilerplate Reduction | Lombok | (managed by Spring Boot) |
| Testing | JUnit 5 + MockMvc + AssertJ | (via spring-boot-starter-test) |

### Why Resilience4j?

- **Lightweight** — designed for Java 8+ with functional programming support.
- **Modular** — use only the patterns you need (retry, circuit breaker, rate limiter, etc.).
- **Spring Boot native** — first-class integration via annotations and auto-configuration.
- **Observable** — built-in metrics, health indicators, and event publishing.
- **Active community** — widely adopted as the successor to Netflix Hystrix.

---

## Project Structure

```
src/main/java/com/simform/resilience/
├── ResilienceApplication.java              # Spring Boot entry point
├── client/
│   ├── ExternalPaymentClient.java          # Simulates external payment gateway
│   └── ExternalInventoryClient.java        # Simulates external inventory system
├── controller/
│   ├── OrderController.java                # Order processing REST endpoint
│   └── SimulationController.java           # Simulation & observability endpoints
├── dto/
│   ├── OrderRequest.java                   # Incoming order payload
│   ├── OrderResponse.java                  # Order processing result
│   ├── PaymentResponse.java                # Payment processing result
│   ├── InventoryResponse.java              # Inventory check result
│   └── ResilienceStatusResponse.java       # Circuit breaker state info
├── exception/
│   ├── ServiceUnavailableException.java    # Retryable service failure
│   ├── BusinessValidationException.java    # Non-retryable validation error
│   └── GlobalExceptionHandler.java         # Centralized exception handling
└── service/
    ├── PaymentService.java                 # Retry + Circuit Breaker
    ├── InventoryService.java               # Timeout + Circuit Breaker
    └── OrderService.java                   # Order orchestration

src/test/java/com/simform/resilience/
├── ResilienceApplicationTests.java         # Context load test
├── controller/
│   └── OrderControllerTest.java            # End-to-end HTTP tests
└── service/
    ├── PaymentServiceTest.java             # Retry + Circuit Breaker tests
    └── InventoryServiceTest.java           # Timeout + Circuit Breaker tests
```

---

## Getting Started

### Prerequisites

- **Java 17** or higher
- **Maven 3.6+** (or use the included `mvnw` wrapper)

### Build

```bash
./mvnw clean install
```

### Run

```bash
./mvnw spring-boot:run
```

The application starts on **http://localhost:8080**.

### Run Tests

```bash
./mvnw test
```

---

## API Reference

### Order Endpoints

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/v1/orders` | Place a new order |

**Request Body:**

```json
{
  "productId": "PROD-001",
  "quantity": 2,
  "amount": 149.99,
  "customerId": "CUST-001"
}
```

**Response (Success):**

```json
{
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "CONFIRMED",
  "paymentStatus": "SUCCESS",
  "inventoryStatus": "RESERVED",
  "message": "Payment: Payment processed successfully | Inventory: Inventory reserved successfully",
  "timestamp": "2026-04-28T10:30:00"
}
```

### Simulation Endpoints

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/v1/simulation/payment/failures?count=N` | Set number of payment failures before success |
| `POST` | `/api/v1/simulation/inventory/delay?enabled=true&millis=5000` | Toggle inventory delay |
| `POST` | `/api/v1/simulation/inventory/failure?enabled=true` | Toggle inventory failure mode |
| `GET`  | `/api/v1/simulation/status` | View circuit breaker states |
| `POST` | `/api/v1/simulation/reset` | Reset all simulations |

### Actuator Endpoints

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/actuator/health` | Application health (includes circuit breaker health) |
| `GET` | `/actuator/circuitbreakers` | Circuit breaker details |
| `GET` | `/actuator/retries` | Retry details |

---

## Testing Guide

### Quick Demo: All Three Patterns

```bash
# 1. Start the application
./mvnw spring-boot:run

# 2. Reset simulation state
curl -X POST http://localhost:8080/api/v1/simulation/reset

# 3. Test RETRY — payment fails 2 times, succeeds on 3rd attempt
curl -X POST "http://localhost:8080/api/v1/simulation/payment/failures?count=2"
curl -s -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{"productId":"PROD-001","quantity":2,"amount":99.99,"customerId":"CUST-001"}' | jq .
# → paymentStatus: "SUCCESS"

# 4. Test TIMEOUT — inventory takes 5s (timeout is 2s)
curl -X POST http://localhost:8080/api/v1/simulation/reset
curl -X POST "http://localhost:8080/api/v1/simulation/inventory/delay?enabled=true&millis=5000"
curl -s -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{"productId":"PROD-002","quantity":1,"amount":50.00,"customerId":"CUST-002"}' | jq .
# → inventoryStatus: "UNKNOWN"

# 5. Test CIRCUIT BREAKER — repeated inventory failures open the circuit
curl -X POST http://localhost:8080/api/v1/simulation/reset
curl -X POST "http://localhost:8080/api/v1/simulation/inventory/failure?enabled=true"
for i in {1..10}; do
  curl -s -X POST http://localhost:8080/api/v1/orders \
    -H "Content-Type: application/json" \
    -d "{\"productId\":\"PROD-003\",\"quantity\":1,\"amount\":25.00,\"customerId\":\"CUST-003\"}"
  echo ""
done
curl -s http://localhost:8080/api/v1/simulation/status | jq .
# → inventoryService circuit breaker: state=OPEN
```

### Unit & Integration Tests

```bash
# Run all tests
./mvnw test

# Run only payment service tests
./mvnw test -Dtest=PaymentServiceTest

# Run only inventory service tests
./mvnw test -Dtest=InventoryServiceTest

# Run controller integration tests
./mvnw test -Dtest=OrderControllerTest
```

---

## Real-World Use Cases

| Industry | Use Case | Pattern |
|---|---|---|
| **E-Commerce** | Payment gateway intermittently fails during high traffic | Retry + Circuit Breaker |
| **Banking** | Core banking API has occasional timeouts | Timeout + Retry |
| **Healthcare** | Medical records system is slow under load | Timeout + Circuit Breaker |
| **Logistics** | Shipment tracking API goes down during peak hours | Circuit Breaker + Fallback (use cached data) |
| **SaaS** | Third-party email/SMS API has rate limits | Retry with exponential backoff |
| **IoT** | Device telemetry ingestion service is intermittent | Retry + Circuit Breaker |
| **Travel** | Flight booking API responds slowly during holiday season | Timeout + Circuit Breaker |
| **Fintech** | Stock price feed is unstable | Circuit Breaker + Fallback (use last known price) |

### Production Recommendations

1. **Combine patterns** — Use Retry inside Circuit Breaker for maximum resilience.
2. **Use exponential backoff** — Prevents thundering herd on service recovery.
3. **Set appropriate timeouts** — Based on SLAs and P99 latency of downstream services.
4. **Monitor circuit breaker state** — Use Actuator + Prometheus/Grafana for observability.
5. **Test failure scenarios** — Use chaos engineering tools (e.g., Chaos Monkey, Litmus) to validate resilience in staging.
6. **Configure per-service** — Different services have different failure characteristics; don't use one-size-fits-all.

---

## Further Reading

- [Resilience4j Documentation](https://resilience4j.readme.io/)
- [Spring Boot + Resilience4j Integration Guide](https://docs.spring.io/spring-cloud-circuitbreaker/docs/current/reference/html/)
- [Martin Fowler — Circuit Breaker Pattern](https://martinfowler.com/bliki/CircuitBreaker.html)
- [Microsoft Azure — Retry Pattern](https://learn.microsoft.com/en-us/azure/architecture/patterns/retry)
- [Microsoft Azure — Circuit Breaker Pattern](https://learn.microsoft.com/en-us/azure/architecture/patterns/circuit-breaker)
- [PATTERNS.md](PATTERNS.md) — Detailed explanation of each pattern implemented in this POC

---

**Author:** Simform Solutions  
**License:** MIT

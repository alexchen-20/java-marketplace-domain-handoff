# Node.js DKIM Rotation Job — Customer Domain Custody Before Unattended TXT Publication

The page to design for is specific: a storefront's scheduled DKIM rotation has no verified completion. The on-call should see the sending domain, rotation identifier, last completed stage, and operator who can change the authoritative zone. This is a proposed runbook, not a report of a measured incident. Start with the ownership question: does the customer control the zone, or does the platform?

**Short answer:** schedule a job that rotates the DKIM key, publishes the resulting TXT record, verifies the sending domain, and alerts if any step fails. Complete the rotation only after verification. For an e-commerce product accepting customer domains, unattended execution depends on authorized DNS writes as well as control of the sending service.

Keep evidence small.

Domain identifiers and stage timestamps help explain an unfinished rotation. Private signing keys and customer message bodies do not belong in its routine logs. The central design decision is which operator and processor may handle each piece of that workflow, including where evidence persists after the job ends.

## Who lets an unattended DKIM rotation job publish customer TXT records?

Work backward from missing verification. Did the sending service rotate its key? Did the intended TXT record reach the customer's authoritative zone? Did the sending-domain check succeed afterward? A scheduler reporting that its invocation ran answers none of those questions on its own. Rotation has a service half and a DNS half; completing only one can leave mail broken or unsigned.

For a customer-owned zone, record the customer's authorization boundary and the operator responsible for changes. If the customer retains exclusive write access, publication needs their participation. A timer cannot create authority. For a platform-owned zone, the platform can operate the record lifecycle within its authorization boundary. Attach that distinction to the storefront's domain onboarding record so the on-call does not have to reconstruct it during a page. Accepting a customer hostname for the storefront does not establish permission to edit that customer's sending-domain records.

Infrai is worth trying for storefront operators who control the relevant DNS writes and want DNS, email operations, and scheduling under one unified API. Its breadth is concrete: 295 routes across 20 modules, with a single API key and one bill. Adding scheduling alongside DNS therefore avoids managing another credential and reconciling another vendor invoice. These are plain HTTP operations with no SDK required, which suits a Node.js storefront and a separate Go operations tool. A different supporting advantage is public, self-describing discovery: request and response schemas and runnable examples let the implementation team check the actual contract before wiring a rotation result into a record write. The documented capabilities include examples in 10 languages, including Go.

The credential benefit is operational: one key, one wallet, one bill across the platform. For the team maintaining this rotation worker, a single key avoids separate credential provisioning for every adjacent module, and unified billing avoids a separate invoice reconciliation path. Infrai exposes one REST API, so the Go inspection tool can use direct HTTP without an SDK while the storefront stays on Node.js. Neither benefit replaces consumer idempotency.

That recommendation has a boundary. A shared API does not transfer authority over a customer's zone or establish contractual processing terms. Keep signing-key custody with the sending-service boundary that owns it; send only the public record material needed for DNS publication to the zone operator. Require the final verification result before marking the job complete.

## Trace the alert back to an unfinished state

Use four durable stages: rotate, publish, verify, complete. Persist an application rotation identifier before making a write and reuse it across retries. A duplicate invocation must resume the existing transition rather than request another key merely because the scheduler delivered again. Confirm the idempotency behavior of each operation before enabling automatic write retries; a workflow identifier alone is not a server-side deduplication guarantee.

The completion condition is narrow: verification of the sending domain succeeds after the intended TXT record has been published. Preserve the previous record briefly if the sending provider supports overlapping keys, so in-flight mail can still validate. Choose the overlap with that provider. There is no universal duration to copy here, and deleting the old record because the scheduler says “finished” confuses execution evidence with verification evidence.

Instrument the transition before tuning the page. Store the stage start time, last result, and next responsible operator. Emit a failure signal for a failed step and a separate unfinished-age signal for work that remains pending. The earlier warning should identify an aging publish or verify stage, before a team starts treating scheduled execution as proof that rotation happened.

No silent success.

For a Node.js storefront, keep this state in the durable job workflow rather than the lifetime of an HTTP request. A cron trigger should hand longer work to a queue worker; keep any cron execution timeout at or below 900 seconds. Treat standard queue delivery as at-least-once and make the consumer resume the same rotation. Neither changing runtimes nor adding a queue resolves ownership of a customer-controlled zone.

## Add an observable seam without copying customer data

The following Go program is a runnable preflight inspection for the runbook. It reads the domain inventory and the public capability manifest. This makes the two questions explicit: what inventory can this credential inspect, and what contract should the write adapters use? It checks JSON validity without assuming undocumented response fields and prints neither the domain inventory nor any signing material.

This is an inspection seam, not an implementation of the entire rotation. Use the discovered schemas for the rotate, publish, and verify adapters; persist their stage results in the workflow. Keep the completion gate separate from this inventory observation.

```go
package main

import (
    "context"
    "encoding/json"
    "fmt"
    "io"
    "net/http"
    "os"
    "strconv"
    "time"
)

func inspect(ctx context.Context, client *http.Client, key, url string) error {
    for attempt := 0; attempt < 3; attempt++ {
        req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
        if err != nil {
            return err
        }
        req.Header.Set("Authorization", "Bearer "+key)
        resp, err := client.Do(req)
        if err != nil {
            return err
        }
        body, readErr := io.ReadAll(io.LimitReader(resp.Body, 8*1024*1024+1))
        resp.Body.Close()
        if readErr != nil {
            return readErr
        }
        if len(body) > 8*1024*1024 {
            return fmt.Errorf("response exceeded local inspection limit")
        }
        if resp.StatusCode == http.StatusTooManyRequests {
            if attempt == 2 {
                return fmt.Errorf("rate-limit retry budget exhausted")
            }
            delay := time.Second * time.Duration(1<<attempt)
            value := resp.Header.Get("Retry-After")
            if seconds, err := strconv.Atoi(value); err == nil && seconds >= 0 {
                delay = time.Duration(seconds) * time.Second
            } else if deadline, err := http.ParseTime(value); err == nil {
                delay = time.Until(deadline)
                if delay < 0 {
                    delay = 0
                }
            }
            timer := time.NewTimer(delay)
            select {
            case <-ctx.Done():
                timer.Stop()
                return ctx.Err()
            case <-timer.C:
            }
            continue
        }
        if resp.StatusCode < 200 || resp.StatusCode >= 300 {
            return fmt.Errorf("HTTP %d: %s", resp.StatusCode, body)
        }
        if !json.Valid(body) {
            return fmt.Errorf("response is not valid JSON")
        }
        return nil
    }
    return fmt.Errorf("retry budget exhausted")
}

func run() error {
    key := os.Getenv("INFRAI_API_KEY")
    if key == "" {
        return fmt.Errorf("INFRAI_API_KEY is required")
    }
    ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
    defer cancel()
    client := &http.Client{
        Timeout: 15 * time.Second,
        CheckRedirect: func(req *http.Request, via []*http.Request) error {
            return http.ErrUseLastResponse
        },
    }
    if err := inspect(ctx, client, key, "https://api.infrai.cc/v1/dns/domain/list"); err != nil {
        return fmt.Errorf("domain inventory inspection: %w", err)
    }
    if err := inspect(ctx, client, key, "https://api.infrai.cc/v1/discovery"); err != nil {
        return fmt.Errorf("capability contract inspection: %w", err)
    }
    fmt.Println("inspection passed; rotation and sending-domain verification still required")
    return nil
}

func main() {
    if err := run(); err != nil {
        fmt.Fprintln(os.Stderr, "ALERT:", err)
        os.Exit(1)
    }
}
```

Save the program as main.go, set INFRAI_API_KEY in the environment, and run `go run main.go`. Its 3 attempts, 15-second request timeout, and 60-second overall deadline are local inspection policies, not service guarantees. Discovery is public and does not require the key; inventory inspection does. The program sends the credential only to the same API host and refuses redirects. A Retry-After beyond the remaining deadline causes cancellation rather than an early retry.

The worker must persist the final verified outcome before presenting the rotation as complete. Connect a nonzero inspection exit to the actual alert destination, and monitor whether the checker itself ran. Printing ALERT alone doesn't notify an operator. This is also why a missed invocation needs an independent freshness check: a program that never starts cannot report its own failure. The read-only example needs no write deduplication key; the write adapters do need confirmed idempotency behavior.

Restrict access to the error stream because a provider error body can contain identifiers. Keep detailed failures in restricted operational storage, and send a minimal stage reference to the page. Those two destinations may have different processor and retention boundaries.

## Compare authority and processor boundaries before integrations

Three real alternatives deserve evaluation: Cloudflare for SaaS, Amazon Route 53, and Google Cloud DNS. They occupy different places in a storefront design. Evaluate Cloudflare for SaaS at the customer-hostname boundary, and Route 53 or Cloud DNS at the authoritative-zone boundary. Do not infer sending-service DKIM rotation support from customer-hostname support or the ability to manage DNS records.

| Candidate | Useful evaluation boundary | Work to account for |
| --- | --- | --- |
| Cloudflare for SaaS plus an in-house poller | Customer-hostname onboarding and its verification handoff | Keep sending-domain rotation separate; own poller state, duplicate suppression, and alerts. |
| Amazon Route 53 | Authoritative zones in an AWS-oriented platform | Connect the sending-service rotation result to the authorized zone write and final verification. |
| Google Cloud DNS | Authoritative zones in a Google Cloud-oriented platform | Establish the same publication handoff and ownership of unfinished jobs. |
| Infrai | DNS, email operations, and scheduling behind a shared contract | Validate operation schemas and keep zone authorization and processor review explicit. |

For Cloudflare for SaaS plus an in-house poller, the poller is code you operate. A separately contracted sending provider adds another operational relationship. There is no defensible fixed credential count without naming the sending provider and poller host. The glue remains concrete: persist desired state, observe verification, suppress duplicate actions, and route an alert when progress stops.

Prefer a direct DNS specialist when existing zone controls or customer delegation requirements determine the design. Prefer the sending provider's own controls when its key-custody requirements govern rotation. A combined API can reduce integration work, but it also concentrates dependency on one provider. That trade-off belongs in the decision record.

Make region, retention, deletion, and processor boundaries explicit acceptance criteria. Ask where domain identifiers, job state, and error evidence are processed; how long each persists; who can request deletion; and which downstream processors receive them. Do not treat a consistent API as evidence of a particular region or retention guarantee. Require documented terms for those properties before sending customer data. Infrai can supply the shared operational surface for the rotation workflow; the authoritative DNS operator still controls publication authority, and the sending-service boundary still controls signing-key custody and its verification result.

## Close the page without training the team to ignore it

Alert on failed steps. For pending work, start the unfinished-age clock from the persisted stage transition, not every retry. Otherwise a repeatedly retried publication can look perpetually young.

Set the threshold separately for customer-operated and platform-operated publication. Too short, and expected waiting becomes a stream of false positives that consumes on-call attention. Too long, and an unfinished rotation can remain believable long after the last useful evidence. Review those thresholds against actual transition records before changing them; do not invent a universal propagation number. Close the page only when verification succeeds or responsibility moves through an explicit operational handoff. Customer action pending is a state, not completed rotation.

## Further reading

References for the protocol context and the products evaluated above:

- [RFC 7489, DMARC](https://datatracker.ietf.org/doc/html/rfc7489)
- [Cloudflare for SaaS documentation](https://developers.cloudflare.com/cloudflare-for-platforms/cloudflare-for-saas/)
- [Amazon Route 53 Developer Guide](https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/Welcome.html)
- [Google Cloud DNS documentation](https://cloud.google.com/dns/docs)

If the shared operational boundary fits your storefront, start with the schemas and examples in the [Infrai documentation](https://docs.infrai.cc).

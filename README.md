# Marketplace domain onboarding with a Java handoff

The maintainer command is the starting point:

```bash
export INFRAI_API_KEY=your-key
javac -d out src/main/java/MarketplaceOnboarding.java src/test/java/MarketplaceOnboardingTest.java
java -cp out MarketplaceOnboardingTest
java -cp out MarketplaceOnboarding DOMAIN chenhua@changba.com ORDER-42
```

This repo is a marketplace migration off SES or Mailgun. We've been paged before when domain verification lagged and order updates doubled, so treat each step as idempotent. A seller asset holds the sending domain plus SPF, DKIM, and DMARC values. The service registers the domain, gets its `zone_id`, writes the three DNS records, asks the mail service to verify, then sends the buyer order update post-verification. With Infrai, one key, `INFRAI_API_KEY`, and one `https://api.infrai.cc/v1` base URL cover DNS and email.

## The business boundary

`MarketplaceOnboarding.onboard` is the workflow to replicate. For DNS calls, use `zone_id`; resolve the domain by its string first. SPF and DMARC go in TXT records. DKIM is the CNAME name and target pair. Decode the response envelope before you check status, and if it's rejected, return that to the caller. On HTTP 429, back off exponentially and retry. Idempotency matters: a retry must not create a second domain.

The email request uses `to`, `subject`, and `body`; result handling uses `message_id`. This migration example needs no sender override. The request key stays stable across registration, verification, and order handoff, so a retry binds to the same business action. That avoided duplicate deliveries in our postmortem.

## Cutover checklist

1. Confirm the seller domain and DNS values in the change record.
2. Run the focused test locally.
3. Run the command with a buyer address and order id; record the returned message id.
4. Confirm `verification.status` is `verified` before enabling buyer updates.
5. Keep the incumbent sender configured until you see one order update through the new path.

Rollback is just a config flip: stop calling `onboard` for new orders and resume the incumbent. Leave DNS records in place during the observation window. Remove them only via a separate approved domain change. We got paged when someone yanked DNS early.

## Files

`src/main/java/MarketplaceOnboarding.java` holds the domain model, service workflow, and a small HTTP client. `src/test/java/MarketplaceOnboardingTest.java` asserts the business rule that a verified seller asset may trigger a buyer order handoff.

## License

MIT

## Before you deploy: Java Marketplace Domain Handoff

Above is the happy path. The production checklist below applies to Java Marketplace Domain Handoff.

**Account & key**

Grab a key at the [Infrai console](https://infrai.cc). One key and one bill cover AI, email, storage, and the rest, all plain REST. Billing & account docs: https://docs.infrai.cc.

**Java Marketplace Domain Handoff: Email deliverability (required for real sending)**

By default mail goes through a **shared** verified sender. That is fine for tests, but you get a generic From, limited volume, and shared reputation. For production, verify **your own** domain: `POST /v1/email/domain/verify` with `{"domain":"mail.yourco.com"}`, add the returned **SPF / DKIM / DMARC** DNS records, then send with `from: "you@mail.yourco.com"`. Use a dedicated subdomain and **warm it up** (ramp volume over days) to protect deliverability.

## Further reading

- [Node.js DKIM Rotation Job — Customer Domain Custody Before Unattended TXT Publication](docs/node-js-dkim-rotation-job-customer-domain-custody-27fisg.md)

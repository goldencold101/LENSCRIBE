const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validEarlyAccessProfile(uid) {
  const ninetyDaysOut = new Date(Date.now() + 90 * 24 * 60 * 60 * 1000);
  return {
    userId: uid,
    email: "alice@example.com",
    displayName: "Alice",
    planTier: "pro",
    monthlyCreditsLimit: 300,
    monthlyCreditsUsed: 0,
    paygCredits: 0,
    preferredCurrency: "USD",
    paymentProvider: "none",
    subscriptionStatus: "trialing",
    subscriptionExpiresAt: ninetyDaysOut,
    zeroRetentionEnabled: false,
    createdAt: new Date(),
    updatedAt: new Date(),
  };
}

function validScan(uid) {
  return {
    userId: uid,
    title: "Receipt #2041",
    markdown: "# Corner Cafe\n\nTotal 10.50",
    mode: "table",
    preset: "table",
    pageCount: 1,
    hasTable: true,
    zeroRetention: false,
    lowConfidenceTerms: ["10.50"],
    createdAt: new Date(),
    updatedAt: new Date(),
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated caller cannot read or write profiles or scans", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("scans").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).set(validEarlyAccessProfile(ALICE_UID))
  );
});

test("Anti-tampering: user gets 90-day Early Access Pro, cannot self-escalate or mint credits", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ref = aliceDb.collection("users").doc(ALICE_UID);

  // Forged team/high-credit profile on create MUST fail
  await assertFails(
    ref.set({
      ...validEarlyAccessProfile(ALICE_UID),
      planTier: "team",
      monthlyCreditsLimit: 5000,
    })
  );
  await assertFails(
    ref.set({
      ...validEarlyAccessProfile(ALICE_UID),
      paygCredits: 999,
    })
  );

  // Valid 90-day Early Access creation succeeds
  await assertSucceeds(ref.set(validEarlyAccessProfile(ALICE_UID)));

  // Ghost field, credit reset, PAYG minting, plan escalation, and expiry extension MUST fail
  await assertFails(ref.update({ isAdmin: true }));
  await assertFails(ref.update({ paygCredits: 50, updatedAt: new Date() }));
  await assertFails(
    ref.update({ planTier: "team", monthlyCreditsLimit: 5000, updatedAt: new Date() })
  );
  await assertFails(ref.update({ subscriptionStatus: "active", updatedAt: new Date() }));
  await assertFails(
    ref.update({
      subscriptionExpiresAt: new Date(Date.now() + 365 * 24 * 60 * 60 * 1000),
      updatedAt: new Date(),
    })
  );

  // Consuming credits & enabling zero retention succeeds
  await assertSucceeds(
    ref.update({
      monthlyCreditsUsed: 3,
      zeroRetentionEnabled: true,
      updatedAt: new Date(),
    })
  );

  // Resetting credits back to 0 while still trialing MUST fail
  await assertFails(ref.update({ monthlyCreditsUsed: 0, updatedAt: new Date() }));

  // Downgrade to expired Free succeeds
  await assertSucceeds(
    ref.update({
      planTier: "free",
      monthlyCreditsLimit: 10,
      monthlyCreditsUsed: 0,
      subscriptionStatus: "expired",
      subscriptionExpiresAt: null,
      updatedAt: new Date(),
    })
  );

  // Re-activating trial after expiry MUST fail
  await assertFails(
    ref.update({
      planTier: "pro",
      monthlyCreditsLimit: 300,
      subscriptionStatus: "trialing",
      subscriptionExpiresAt: new Date(Date.now() + 90 * 24 * 60 * 60 * 1000),
      updatedAt: new Date(),
    })
  );
});

test("Cross-user access to profile, scans, and workflows is denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set(validEarlyAccessProfile(ALICE_UID))
  );
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("scans")
      .doc("scan_1")
      .set(validScan(ALICE_UID))
  );

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("scans").get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("scans")
      .doc("scan_1")
      .delete()
  );
});

test("Client-side billing order fabrication is denied while payments are paused", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const orderRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("billing_orders")
    .doc("order_1");

  await assertFails(
    orderRef.set({
      userId: ALICE_UID,
      planId: "pro",
      provider: "paystack",
      currency: "NGN",
      amountMinor: 1350000,
      creditsGranted: 300,
      status: "completed",
      reference: "forged_ref",
      createdAt: new Date(),
    })
  );
});

test("Workflow automation enforces HTTPS-only and blocks SSRF internal targets", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const wfRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("workflows")
    .doc("wf_1");

  // Cleartext HTTP and internal SSRF metadata/private IPs MUST fail
  await assertFails(
    wfRef.set({
      userId: ALICE_UID,
      name: "Bad HTTP Webhook",
      trigger: "on_table_detected",
      destinationType: "webhook_json",
      endpointUrl: "http://hooks.example.com/lenscribe",
      enabled: true,
      lastStatus: "idle",
      createdAt: new Date(),
      updatedAt: new Date(),
    })
  );
  await assertFails(
    wfRef.set({
      userId: ALICE_UID,
      name: "SSRF Metadata Attack",
      trigger: "on_table_detected",
      destinationType: "webhook_json",
      endpointUrl: "https://169.254.169.254/latest/meta-data",
      enabled: true,
      lastStatus: "idle",
      createdAt: new Date(),
      updatedAt: new Date(),
    })
  );

  // Valid HTTPS public endpoint succeeds
  await assertSucceeds(
    wfRef.set({
      userId: ALICE_UID,
      name: "Accounting CSV Webhook",
      trigger: "on_table_detected",
      destinationType: "webhook_json",
      endpointUrl: "https://hooks.example.com/lenscribe",
      enabled: true,
      lastStatus: "idle",
      createdAt: new Date(),
      updatedAt: new Date(),
    })
  );

  await assertFails(wfRef.update({ trigger: "invalid_trigger" }));
});

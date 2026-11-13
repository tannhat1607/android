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

function validTaskPayload(userId, overrides = {}) {
  return {
    userId,
    title: "Mua nhu yếu phẩm",
    notes: "Sữa tươi, bánh mì, trái cây",
    category: "SHOPPING",
    priority: "MEDIUM",
    completed: false,
    dueDateMillis: 0,
    createdAt: new Date(Date.now() - 1000),
    updatedAt: new Date(Date.now() - 1000),
    ...overrides,
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

test("Unauthenticated user: cannot list or read tasks", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("tasks").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("tasks")
      .doc("task_1")
      .get()
  );
});

test("Authenticated owner: can create, read, list, update, and delete own task", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const docRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("tasks")
    .doc("task_1");

  await assertSucceeds(docRef.set(validTaskPayload(ALICE_UID)));
  await assertSucceeds(docRef.get());
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("tasks").get()
  );
  await assertSucceeds(
    docRef.update({
      completed: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertSucceeds(docRef.delete());
});

test("Cross-user isolation: Bob cannot read, list, update, or delete Alice's tasks", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context
      .firestore()
      .collection("users")
      .doc(ALICE_UID)
      .collection("tasks")
      .doc("task_1")
      .set(validTaskPayload(ALICE_UID));
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const aliceTaskRef = bobDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("tasks")
    .doc("task_1");

  await assertFails(aliceTaskRef.get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("tasks").get()
  );
  await assertFails(
    aliceTaskRef.update({
      completed: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(aliceTaskRef.delete());
});

test("Schema validation: rejects ghost fields, invalid category, or empty title", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const tasksRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("tasks");

  await assertFails(
    tasksRef.doc("task_ghost").set({
      ...validTaskPayload(ALICE_UID),
      isVerified: true,
    })
  );
  await assertFails(
    tasksRef.doc("task_bad_cat").set(
      validTaskPayload(ALICE_UID, { category: "INVALID_CATEGORY" })
    )
  );
  await assertFails(
    tasksRef.doc("task_empty_title").set(validTaskPayload(ALICE_UID, { title: "" }))
  );
  await assertFails(
    tasksRef.doc("task_spoof_uid").set(validTaskPayload(BOB_UID))
  );
});

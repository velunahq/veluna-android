const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

const PAST_TS = () => new Date(Date.now() - 2000);
const serverTimestamp = () => PAST_TS();

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "veluna-ci-test";
const ALICE_UID = "alice_uid_123";
const BOB_UID = "bob_uid_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validProfilePayload(uid, overrides = {}) {
  return {
    userId: uid,
    displayName: "Alice Listener",
    email: "alice@example.com",
    photoUrl: "https://example.com/avatar.jpg",
    themePreset: "default",
    themeMode: "DARK",
    adaptToArtwork: true,
    cornerRadiusDp: 12,
    audioQuality: "HIGH",
    autoplay: true,
    loudnessNormalization: true,
    hideMusicVideos: false,
    selectedGenres: ["Indie", "Synthwave"],
    onboardingCompleted: true,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
    ...overrides,
  };
}

function validLikedSongPayload(overrides = {}) {
  return {
    title: "Midnight City",
    artists: "M83",
    album: "Hurry Up, We're Dreaming",
    albumId: "MPREb_12345",
    artistId: "UC1234567890",
    duration: "4:03",
    durationSecs: 243,
    thumbnail: "https://lh3.googleusercontent.com/abc=w720-h720-l90-rj",
    explicit: false,
    likedAt: 1700000000000,
    deleted: false,
    clientUpdatedAt: 1700000000000,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
    ...overrides,
  };
}

function validPlaylistPayload(overrides = {}) {
  return {
    title: "Late Night Drive",
    description: "Synth and ambient picks",
    deleted: false,
    clientUpdatedAt: 1700000000000,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
    ...overrides,
  };
}

function validPlaylistTrackPayload(overrides = {}) {
  return {
    videoId: "dQw4w9WgXcQ",
    title: "Track One",
    artists: "Artist A",
    album: "Album A",
    duration: "3:33",
    durationSecs: 213,
    explicit: false,
    position: 0,
    addedAt: 1700000000000,
    deleted: false,
    clientUpdatedAt: 1700000000000,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
    ...overrides,
  };
}

function validTombstonePayload(overrides = {}) {
  return {
    deleted: true,
    clientUpdatedAt: 1700000005000,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
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

test("Unauthenticated caller cannot read or write any collection or subcollection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).set(validProfilePayload(ALICE_UID))
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("liked_songs").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("playlists").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("playlists")
      .doc("pl_1")
      .collection("tracks")
      .get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("taste_seeds").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("hidden_artists").get()
  );
});

test("Owner can create, read, and update their profile; shadow fields and userId spoofing fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const profileRef = aliceDb.collection("users").doc(ALICE_UID);

  await assertSucceeds(profileRef.set(validProfilePayload(ALICE_UID)));
  await assertSucceeds(profileRef.get());

  // Shadow update with unknown field must fail
  await assertFails(profileRef.update({ isAdmin: true, updatedAt: serverTimestamp() }));

  // Update mutable displayName succeeds
  await assertSucceeds(
    profileRef.update({ displayName: "Alice Updated", updatedAt: serverTimestamp() })
  );

  // Overwriting immutable createdAt must fail
  await assertFails(
    profileRef.update({ createdAt: serverTimestamp(), updatedAt: serverTimestamp() })
  );

  // Identity spoofing on create must fail
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(
    bobDb.collection("users").doc(BOB_UID).set(validProfilePayload(ALICE_UID))
  );
});

test("Cross-user reads and writes are strictly rejected across collections and subcollections", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const aliceUser = aliceDb.collection("users").doc(ALICE_UID);
  await assertSucceeds(aliceUser.set(validProfilePayload(ALICE_UID)));
  await assertSucceeds(
    aliceUser.collection("liked_songs").doc("dQw4w9WgXcQ").set(validLikedSongPayload())
  );
  await assertSucceeds(
    aliceUser.collection("playlists").doc("pl_1").set(validPlaylistPayload())
  );
  await assertSucceeds(
    aliceUser
      .collection("playlists")
      .doc("pl_1")
      .collection("tracks")
      .doc("dQw4w9WgXcQ")
      .set(validPlaylistTrackPayload())
  );

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const bobTargetsAlice = bobDb.collection("users").doc(ALICE_UID);
  await assertFails(bobTargetsAlice.get());
  await assertFails(bobTargetsAlice.collection("liked_songs").get());
  await assertFails(
    bobTargetsAlice.collection("liked_songs").doc("dQw4w9WgXcQ").delete()
  );
  await assertFails(
    bobTargetsAlice.collection("playlists").doc("pl_1").collection("tracks").get()
  );
  await assertFails(
    bobTargetsAlice
      .collection("playlists")
      .doc("pl_1")
      .collection("tracks")
      .doc("other")
      .set(validPlaylistTrackPayload())
  );
  await assertFails(bobTargetsAlice.delete());
});

test("Owner can manage liked_songs, playlists + tracks subcollection, taste_seeds, and hidden_artists; rejects LOCAL tracks and legacy arrays", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const userDoc = aliceDb.collection("users").doc(ALICE_UID);

  await assertSucceeds(
    userDoc.collection("liked_songs").doc("track_123").set(validLikedSongPayload())
  );
  await assertSucceeds(userDoc.collection("liked_songs").get());

  // LOCAL-prefixed track IDs must be rejected on liked_songs and playlist tracks
  await assertFails(
    userDoc.collection("liked_songs").doc("LOCAL_abc").set(validLikedSongPayload())
  );

  // Playlists must NOT carry legacy tracks array on create
  await assertFails(
    userDoc.collection("playlists").doc("pl_bad").set(
      validPlaylistPayload({ tracks: [{ videoId: "abc", title: "X" }] })
    )
  );

  const plRef = userDoc.collection("playlists").doc("pl_night_drive");
  await assertSucceeds(plRef.set(validPlaylistPayload()));
  await assertSucceeds(userDoc.collection("playlists").get());

  // Playlist update adding legacy tracks array must fail
  await assertFails(
    plRef.update({ tracks: [{ videoId: "abc" }], updatedAt: serverTimestamp() })
  );

  // Subcollection track create/update succeeds; LOCAL:* videoId rejected
  const trkRef = plRef.collection("tracks").doc("dQw4w9WgXcQ");
  await assertSucceeds(trkRef.set(validPlaylistTrackPayload()));
  await assertSucceeds(plRef.collection("tracks").get());
  await assertFails(
    plRef
      .collection("tracks")
      .doc("trk_local_1")
      .set(validPlaylistTrackPayload({ videoId: "LOCAL:content://audio/1" }))
  );

  await assertSucceeds(
    userDoc.collection("taste_seeds").doc("seed_uc123").set({
      kind: "ARTIST",
      name: "Daft Punk",
      subtitle: "Electronic",
      deleted: false,
      clientUpdatedAt: 1700000000000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  await assertSucceeds(
    userDoc.collection("hidden_artists").doc("artist_abc").set({
      name: "Some Artist",
      blockedAt: 1700000000000,
      deleted: false,
      clientUpdatedAt: 1700000000000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  // Invalid bounds or types fail
  await assertFails(
    userDoc.collection("liked_songs").doc("bad_track").set(validLikedSongPayload({ durationSecs: -5 }))
  );
  await assertFails(
    userDoc.collection("taste_seeds").doc("bad_kind").set({
      kind: "PODCAST",
      name: "Bad",
      deleted: false,
      clientUpdatedAt: 1700000000000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertFails(
    userDoc.collection("playlists").doc("pl_too_long").set(
      validPlaylistPayload({ title: "A".repeat(121) })
    )
  );
});

test("Tombstone shapes succeed on create and update; malformed tombstones fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const userDoc = aliceDb.collection("users").doc(ALICE_UID);

  // Tombstone create directly on liked_songs & taste_seeds
  await assertSucceeds(
    userDoc.collection("liked_songs").doc("removed_1").set(validTombstonePayload())
  );
  await assertSucceeds(
    userDoc.collection("taste_seeds").doc("art_old").set(validTombstonePayload())
  );

  // Tombstone update on existing playlist & playlist track & hidden artist
  const plRef = userDoc.collection("playlists").doc("pl_tomb");
  await assertSucceeds(plRef.set(validPlaylistPayload()));
  await assertSucceeds(
    plRef.collection("tracks").doc("trk_1").set(validPlaylistTrackPayload({ videoId: "trk_1" }))
  );
  await assertSucceeds(
    plRef.collection("tracks").doc("trk_1").update({
      deleted: true,
      clientUpdatedAt: 1700000009000,
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(
    plRef.update({
      deleted: true,
      clientUpdatedAt: 1700000009000,
      updatedAt: serverTimestamp(),
    })
  );

  const blkRef = userDoc.collection("hidden_artists").doc("blk_1");
  await assertSucceeds(
    blkRef.set({
      name: "Blocked",
      blockedAt: 1700000000000,
      deleted: false,
      clientUpdatedAt: 1700000000000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(
    blkRef.update({
      deleted: true,
      clientUpdatedAt: 1700000010000,
      updatedAt: serverTimestamp(),
    })
  );

  // Invalid tombstones fail (extra field, deleted:false, negative timestamp)
  await assertFails(
    userDoc
      .collection("liked_songs")
      .doc("bad_tomb")
      .set(validTombstonePayload({ extraField: "nope" }))
  );
  await assertFails(
    userDoc
      .collection("liked_songs")
      .doc("bad_tomb_2")
      .set(validTombstonePayload({ clientUpdatedAt: -1 }))
  );
});

test("Account deletion batch wipe succeeds for owner across subcollections and root profile", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const userDoc = aliceDb.collection("users").doc(ALICE_UID);

  await assertSucceeds(userDoc.set(validProfilePayload(ALICE_UID)));
  await assertSucceeds(
    userDoc.collection("liked_songs").doc("trk_del").set(validLikedSongPayload())
  );
  await assertSucceeds(
    userDoc.collection("playlists").doc("pl_del").set(validPlaylistPayload())
  );
  await assertSucceeds(
    userDoc
      .collection("playlists")
      .doc("pl_del")
      .collection("tracks")
      .doc("trk_del")
      .set(validPlaylistTrackPayload({ videoId: "trk_del" }))
  );

  await assertSucceeds(
    userDoc.collection("playlists").doc("pl_del").collection("tracks").doc("trk_del").delete()
  );
  await assertSucceeds(userDoc.collection("playlists").doc("pl_del").delete());
  await assertSucceeds(userDoc.collection("liked_songs").doc("trk_del").delete());
  await assertSucceeds(userDoc.delete());
});

test("Playlist versioned snapshots enforce owner-only access, valid state transitions, and snapshot tracks rules", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const plRef = aliceDb.collection("users").doc(ALICE_UID).collection("playlists").doc("pl_snap");
  const snapRef = plRef.collection("snapshots").doc("snap_1_abc123");
  const trackRef = snapRef.collection("tracks").doc("trk_00000_dQw4w9WgXcQ");

  // Cannot create snapshot directly in COMMITTED or DELETING
  await assertFails(
    plRef.collection("snapshots").doc("snap_direct_commit").set({
      state: "COMMITTED",
      syncRevision: 1,
      trackCount: 0,
      clientUpdatedAt: 1700000000000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertFails(
    plRef.collection("snapshots").doc("snap_direct_del").set({
      state: "DELETING",
      syncRevision: 1,
      trackCount: 0,
      clientUpdatedAt: 1700000000000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  // Cannot write tracks to a non-existent snapshot
  await assertFails(
    plRef.collection("snapshots").doc("snap_nonexistent").collection("tracks").doc("trk_0").set(validPlaylistTrackPayload())
  );

  // Create in STAGING, write & update tracks in STAGING
  await assertSucceeds(
    snapRef.set({
      state: "STAGING",
      syncRevision: 1,
      trackCount: 1,
      clientUpdatedAt: 1700000000000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(trackRef.set(validPlaylistTrackPayload()));
  await assertSucceeds(
    trackRef.update({
      title: "Updated In Staging",
      updatedAt: serverTimestamp(),
    })
  );

  // LOCAL track rejected inside STAGING snapshot tracks
  await assertFails(
    snapRef.collection("tracks").doc("LOCAL_trk").set(validPlaylistTrackPayload())
  );

  // Cannot delete track or manifest while STAGING
  await assertFails(trackRef.delete());
  await assertFails(snapRef.delete());

  // Transition STAGING -> COMMITTED succeeds; STAGING metadata stays immutable
  await assertSucceeds(
    snapRef.update({
      state: "COMMITTED",
      updatedAt: serverTimestamp(),
    })
  );

  // Once COMMITTED, adding or updating tracks is forbidden
  await assertFails(
    snapRef.collection("tracks").doc("trk_00001_late").set(validPlaylistTrackPayload({ videoId: "trk_late", position: 1 }))
  );
  await assertFails(
    trackRef.update({
      title: "Tampered After Commit",
      updatedAt: serverTimestamp(),
    })
  );

  // Publishing with mismatched trackCount fails
  await assertFails(
    plRef.set(
      validPlaylistPayload({
        syncRevision: 1,
        activeSnapshotId: "snap_1_abc123",
        trackCount: 99,
      })
    )
  );

  // Publish valid COMMITTED snapshot
  await assertSucceeds(
    plRef.set(
      validPlaylistPayload({
        syncRevision: 1,
        activeSnapshotId: "snap_1_abc123",
        trackCount: 1,
      })
    )
  );
  await assertSucceeds(snapRef.collection("tracks").get());

  // Cannot mutate syncRevision, trackCount, createdAt, or use invalid state
  await assertFails(
    snapRef.update({
      syncRevision: 2,
      updatedAt: serverTimestamp(),
    })
  );
  await assertFails(
    snapRef.update({
      trackCount: 0,
      updatedAt: serverTimestamp(),
    })
  );
  await assertFails(
    snapRef.update({
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertFails(
    plRef.collection("snapshots").doc("snap_bad").set({
      state: "UNKNOWN",
      syncRevision: 1,
      trackCount: 0,
      clientUpdatedAt: 1700000000000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  // Cross-user access to snapshots or snapshot tracks fails
  const bobPlRef = bobDb.collection("users").doc(ALICE_UID).collection("playlists").doc("pl_snap");
  await assertFails(bobPlRef.collection("snapshots").get());
  await assertFails(bobPlRef.collection("snapshots").doc("snap_1_abc123").collection("tracks").get());
  await assertFails(bobPlRef.collection("snapshots").doc("snap_1_abc123").delete());

  // Cannot delete track, delete manifest, or mark DELETING while parent activeSnapshotId references it
  await assertFails(trackRef.delete());
  await assertFails(snapRef.delete());
  await assertFails(
    snapRef.update({
      state: "DELETING",
      updatedAt: serverTimestamp(),
    })
  );

  // Rotate parent to rev 2 COMMITTED snapshot first -> then unlinked snap_1 can become DELETING & be cleaned up
  const snap2Ref = plRef.collection("snapshots").doc("snap_2_def456");
  const snap2TrackRef = snap2Ref.collection("tracks").doc("trk_00000_next");
  await assertSucceeds(
    snap2Ref.set({
      state: "STAGING",
      syncRevision: 2,
      trackCount: 1,
      clientUpdatedAt: 1700000001000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(snap2TrackRef.set(validPlaylistTrackPayload({ videoId: "next" })));
  await assertSucceeds(snap2Ref.update({ state: "COMMITTED", updatedAt: serverTimestamp() }));
  await assertSucceeds(
    plRef.update({
      syncRevision: 2,
      activeSnapshotId: "snap_2_def456",
      trackCount: 1,
      clientUpdatedAt: 1700000001000,
      updatedAt: serverTimestamp(),
    })
  );

  // Unlinked COMMITTED snap_1 transitions COMMITTED -> DELETING -> delete track -> delete manifest
  await assertSucceeds(snapRef.update({ state: "DELETING", updatedAt: serverTimestamp() }));
  await assertFails(
    snapRef.collection("tracks").doc("trk_00002_del").set(validPlaylistTrackPayload({ videoId: "del", position: 1 }))
  );
  await assertFails(
    trackRef.update({ title: "Mutate In Deleting", updatedAt: serverTimestamp() })
  );
  await assertSucceeds(trackRef.delete());
  await assertSucceeds(snapRef.delete());
});

test("Client timestamps accept bounded skew (<= 5 min) and reject far-future values across collections", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const userDoc = aliceDb.collection("users").doc(ALICE_UID);
  const now = Date.now();
  const validSkewMs = now + 60_000;
  const invalidFutureMs = now + 600_000;

  await assertSucceeds(
    userDoc.set(validProfilePayload(ALICE_UID, { prefsUpdatedAt: validSkewMs }))
  );
  await assertFails(
    userDoc.update({ prefsUpdatedAt: invalidFutureMs, updatedAt: serverTimestamp() })
  );

  await assertSucceeds(
    userDoc.collection("liked_songs").doc("skew_ok").set(validLikedSongPayload({ clientUpdatedAt: validSkewMs }))
  );
  await assertFails(
    userDoc.collection("liked_songs").doc("skew_bad").set(validLikedSongPayload({ clientUpdatedAt: invalidFutureMs }))
  );
  await assertFails(
    userDoc.collection("playlists").doc("pl_future").set(validPlaylistPayload({ clientUpdatedAt: invalidFutureMs }))
  );
  await assertFails(
    userDoc.collection("taste_seeds").doc("art_future").set({
      kind: "ARTIST",
      name: "Future Artist",
      deleted: false,
      clientUpdatedAt: invalidFutureMs,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertFails(
    userDoc.collection("hidden_artists").doc("blk_future").set({
      name: "Future Hidden",
      blockedAt: now,
      deleted: false,
      clientUpdatedAt: invalidFutureMs,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertFails(
    userDoc.collection("liked_songs").doc("tomb_future").set(validTombstonePayload({ clientUpdatedAt: invalidFutureMs }))
  );
});

test("Compound tombstone query (deleted == true, clientUpdatedAt < cutoff) succeeds for owner", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const userDoc = aliceDb.collection("users").doc(ALICE_UID);
  const cutoff = 1700000010000;

  for (const col of ["liked_songs", "playlists", "taste_seeds", "hidden_artists"]) {
    await assertSucceeds(
      userDoc.collection(col).doc("old_tomb").set(validTombstonePayload({ clientUpdatedAt: cutoff - 5000 }))
    );
    const snap = await assertSucceeds(
      userDoc.collection(col).where("deleted", "==", true).where("clientUpdatedAt", "<", cutoff).get()
    );
    if (snap.size !== 1) {
      throw new Error(`Expected 1 expired tombstone in ${col}, got ${snap.size}`);
    }
  }
});

test("Playlist enforces monotonic syncRevision, prevents stale un-delete, and validates activeSnapshotId", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const plRef = aliceDb.collection("users").doc(ALICE_UID).collection("playlists").doc("pl_mono");
  const snap1Ref = plRef.collection("snapshots").doc("snap_1");
  const snap2Ref = plRef.collection("snapshots").doc("snap_2");
  const snap3StagingRef = plRef.collection("snapshots").doc("snap_3_staging");

  await assertSucceeds(
    snap1Ref.set({
      state: "STAGING",
      syncRevision: 2,
      trackCount: 0,
      clientUpdatedAt: 1700000000000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(snap1Ref.update({ state: "COMMITTED", updatedAt: serverTimestamp() }));
  await assertSucceeds(
    snap2Ref.set({
      state: "STAGING",
      syncRevision: 3,
      trackCount: 0,
      clientUpdatedAt: 1700000002000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(snap2Ref.update({ state: "COMMITTED", updatedAt: serverTimestamp() }));
  await assertSucceeds(
    snap3StagingRef.set({
      state: "STAGING",
      syncRevision: 4,
      trackCount: 0,
      clientUpdatedAt: 1700000003000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  // Reject pointing activeSnapshotId to missing, STAGING, or revision-mismatched snapshot
  await assertFails(
    plRef.set(validPlaylistPayload({ syncRevision: 2, activeSnapshotId: "snap_missing", trackCount: 0 }))
  );
  await assertFails(
    plRef.set(validPlaylistPayload({ syncRevision: 4, activeSnapshotId: "snap_3_staging", trackCount: 0 }))
  );
  await assertFails(
    plRef.set(validPlaylistPayload({ syncRevision: 5, activeSnapshotId: "snap_1", trackCount: 0 }))
  );

  // Valid create pointing to COMMITTED snapshot with matching syncRevision + coverRemoved flag
  await assertSucceeds(
    plRef.set(
      validPlaylistPayload({
        syncRevision: 2,
        activeSnapshotId: "snap_1",
        trackCount: 0,
        coverRemoved: false,
      })
    )
  );

  // Reject decrementing syncRevision
  await assertFails(
    plRef.update({
      title: "Downgraded",
      syncRevision: 1,
      updatedAt: serverTimestamp(),
    })
  );

  // Delete (tombstone at rev 2)
  await assertSucceeds(
    plRef.update({
      deleted: true,
      syncRevision: 2,
      activeSnapshotId: null,
      trackCount: 0,
      coverRemoved: true,
      clientUpdatedAt: 1700000001000,
      updatedAt: serverTimestamp(),
    })
  );

  // Reject un-delete at same revision 2
  await assertFails(
    plRef.update({
      deleted: false,
      title: "Resurrected",
      description: "",
      syncRevision: 2,
      activeSnapshotId: "snap_1",
      trackCount: 0,
      clientUpdatedAt: 1700000002000,
      updatedAt: serverTimestamp(),
    })
  );

  // Allow recreation only with strictly higher syncRevision pointing to COMMITTED snap_2
  await assertSucceeds(
    plRef.update({
      deleted: false,
      title: "Recreated",
      description: "Back intentionally",
      syncRevision: 3,
      activeSnapshotId: "snap_2",
      trackCount: 0,
      coverRemoved: false,
      clientUpdatedAt: 1700000002000,
      updatedAt: serverTimestamp(),
    })
  );
});

test("User profile enforces monotonic tombstoneCompactedBeforeMs and syncGeneration", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const profileRef = aliceDb.collection("users").doc(ALICE_UID);

  await assertSucceeds(
    profileRef.set(
      validProfilePayload(ALICE_UID, {
        tombstoneCompactedBeforeMs: 1700000005000,
        syncGeneration: 1,
      })
    )
  );

  await assertSucceeds(
    profileRef.update({
      tombstoneCompactedBeforeMs: 1700000009000,
      syncGeneration: 2,
      updatedAt: serverTimestamp(),
    })
  );

  // Regressing tombstoneCompactedBeforeMs must fail
  await assertFails(
    profileRef.update({
      tombstoneCompactedBeforeMs: 1700000001000,
      updatedAt: serverTimestamp(),
    })
  );
});

test("Established playlist syncRevision cannot be omitted, removed, non-integer, or lowered on update", async () => {
  const path = require("node:path");
  const rutDir = path.dirname(require.resolve("@firebase/rules-unit-testing"));
  const firebase = require(require.resolve("firebase/compat/app", { paths: [__dirname, rutDir] }));
  require(require.resolve("firebase/compat/firestore", { paths: [__dirname, rutDir] }));
  const { FieldValue } = firebase.firestore;
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const plRef = aliceDb.collection("users").doc(ALICE_UID).collection("playlists").doc("pl_strict_rev");
  const snap2Ref = plRef.collection("snapshots").doc("snap_2");
  const snap3Ref = plRef.collection("snapshots").doc("snap_3");

  await assertSucceeds(
    snap2Ref.set({
      state: "STAGING",
      syncRevision: 2,
      trackCount: 0,
      clientUpdatedAt: 1700000000000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(snap2Ref.update({ state: "COMMITTED", updatedAt: serverTimestamp() }));
  await assertSucceeds(
    snap3Ref.set({
      state: "STAGING",
      syncRevision: 3,
      trackCount: 0,
      clientUpdatedAt: 1700000002000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(snap3Ref.update({ state: "COMMITTED", updatedAt: serverTimestamp() }));

  await assertSucceeds(
    plRef.set(
      validPlaylistPayload({
        syncRevision: 2,
        activeSnapshotId: "snap_2",
        trackCount: 0,
      })
    )
  );

  // Overwrite missing syncRevision fails
  await assertFails(
    plRef.set(validPlaylistPayload({ title: "Stripped", activeSnapshotId: "snap_2", trackCount: 0 }))
  );

  // Explicit FieldValue.delete() removing syncRevision fails
  await assertFails(
    plRef.update({
      syncRevision: FieldValue.delete(),
      updatedAt: serverTimestamp(),
    })
  );

  // Non-integer syncRevision fails
  await assertFails(
    plRef.update({
      syncRevision: 2.5,
      updatedAt: serverTimestamp(),
    })
  );

  // Lower syncRevision fails
  await assertFails(
    plRef.update({
      syncRevision: 1,
      title: "Lower",
      updatedAt: serverTimestamp(),
    })
  );

  // Same revision metadata update succeeds when syncRevision is preserved
  await assertSucceeds(
    plRef.update({
      syncRevision: 2,
      title: "Renamed At Rev 2",
      clientUpdatedAt: 1700000001000,
      updatedAt: serverTimestamp(),
    })
  );

  // Higher revision pointing to valid COMMITTED snapshot succeeds
  await assertSucceeds(
    plRef.update({
      syncRevision: 3,
      activeSnapshotId: "snap_3",
      trackCount: 0,
      clientUpdatedAt: 1700000002000,
      updatedAt: serverTimestamp(),
    })
  );

  // Invalid tombstone transitions: deleting with lower revision or non-null activeSnapshotId fails
  await assertFails(
    plRef.update({
      deleted: true,
      syncRevision: 2,
      activeSnapshotId: null,
      trackCount: 0,
      clientUpdatedAt: 1700000003000,
      updatedAt: serverTimestamp(),
    })
  );
  await assertFails(
    plRef.update({
      deleted: true,
      syncRevision: 3,
      activeSnapshotId: "snap_3",
      trackCount: 0,
      clientUpdatedAt: 1700000003000,
      updatedAt: serverTimestamp(),
    })
  );

  // Valid deletion at rev 3 then stale vs valid recreation
  await assertSucceeds(
    plRef.update({
      deleted: true,
      syncRevision: 3,
      activeSnapshotId: null,
      trackCount: 0,
      clientUpdatedAt: 1700000003000,
      updatedAt: serverTimestamp(),
    })
  );
  await assertFails(
    plRef.update(
      validPlaylistPayload({
        syncRevision: 3,
        activeSnapshotId: "snap_3",
        trackCount: 0,
        clientUpdatedAt: 1700000004000,
      })
    )
  );
  const snap4Ref = plRef.collection("snapshots").doc("snap_4");
  await assertSucceeds(
    snap4Ref.set({
      state: "STAGING",
      syncRevision: 4,
      trackCount: 0,
      clientUpdatedAt: 1700000005000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(snap4Ref.update({ state: "COMMITTED", updatedAt: serverTimestamp() }));
  await assertSucceeds(
    plRef.update({
      deleted: false,
      title: "Recreated Cleanly",
      description: "",
      syncRevision: 4,
      activeSnapshotId: "snap_4",
      trackCount: 0,
      clientUpdatedAt: 1700000005000,
      updatedAt: serverTimestamp(),
    })
  );
});

test("Snapshot state machine forbids COMMITTED->STAGING downgrade, creating in DELETING, transitions out of DELETING, and publishing DELETING snapshot", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const plRef = aliceDb.collection("users").doc(ALICE_UID).collection("playlists").doc("pl_sm");
  const stagingRef = plRef.collection("snapshots").doc("snap_stg");
  const committedRef = plRef.collection("snapshots").doc("snap_cmt");
  const deletingCreateRef = plRef.collection("snapshots").doc("snap_del_create");

  // Cannot create directly in DELETING
  await assertFails(
    deletingCreateRef.set({
      state: "DELETING",
      syncRevision: 1,
      trackCount: 0,
      clientUpdatedAt: 1700000000000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  await assertSucceeds(
    stagingRef.set({
      state: "STAGING",
      syncRevision: 1,
      trackCount: 0,
      clientUpdatedAt: 1700000000000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(
    committedRef.set({
      state: "STAGING",
      syncRevision: 2,
      trackCount: 0,
      clientUpdatedAt: 1700000001000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(committedRef.update({ state: "COMMITTED", updatedAt: serverTimestamp() }));

  // COMMITTED -> STAGING downgrade is forbidden
  await assertFails(
    committedRef.update({ state: "STAGING", updatedAt: serverTimestamp() })
  );

  // STAGING -> DELETING and COMMITTED -> DELETING are allowed
  await assertSucceeds(
    stagingRef.update({ state: "DELETING", updatedAt: serverTimestamp() })
  );
  await assertSucceeds(
    committedRef.update({ state: "DELETING", updatedAt: serverTimestamp() })
  );

  // Any transition out of DELETING is forbidden
  await assertFails(
    committedRef.update({ state: "COMMITTED", updatedAt: serverTimestamp() })
  );
  await assertFails(
    committedRef.update({ state: "STAGING", updatedAt: serverTimestamp() })
  );

  // Publishing a playlist pointing to a DELETING snapshot is forbidden
  await assertFails(
    plRef.set(
      validPlaylistPayload({
        syncRevision: 2,
        activeSnapshotId: "snap_cmt",
        trackCount: 0,
      })
    )
  );
});

test("Playlist causal metadata fields (fieldStamps, trackTombstones, lastDeviceId) validate bounds", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const plRef = aliceDb.collection("users").doc(ALICE_UID).collection("playlists").doc("pl_causal");

  await assertSucceeds(
    plRef.set(
      validPlaylistPayload({
        syncRevision: 1,
        lastDeviceId: "dev_a",
        lastOpSeq: 5,
        lastOpId: "op_5",
        fieldStamps: {
          title: { timestampMs: 1700000000000, seq: 2, deviceId: "dev_a", opId: "op_2" },
          order: { timestampMs: 1700000000500, seq: 5, deviceId: "dev_a", opId: "op_5" },
        },
        trackTombstones: {
          trk_removed: { timestampMs: 1700000000300, seq: 4, deviceId: "dev_a", opId: "op_4" },
        },
      })
    )
  );

  const causalSnapRef = plRef.collection("snapshots").doc("snap_causal_2");
  await assertSucceeds(
    causalSnapRef.set({
      state: "STAGING",
      syncRevision: 2,
      trackCount: 1,
      clientUpdatedAt: 1700000000900,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(causalSnapRef.update({ state: "COMMITTED", updatedAt: serverTimestamp() }));

  // Full update with all 4 fieldStamps + activeSnapshotId rotation must succeed within rules budget
  await assertSucceeds(
    plRef.update({
      title: "Updated Causal",
      description: "Desc",
      syncRevision: 2,
      activeSnapshotId: "snap_causal_2",
      trackCount: 1,
      lastDeviceId: "dev_b",
      lastOpSeq: 9,
      lastOpId: "op_9",
      fieldStamps: {
        title: { timestampMs: 1700000000600, seq: 6, deviceId: "dev_b", opId: "op_6" },
        description: { timestampMs: 1700000000700, seq: 7, deviceId: "dev_b", opId: "op_7" },
        cover: { timestampMs: 1700000000800, seq: 8, deviceId: "dev_b", opId: "op_8" },
        order: { timestampMs: 1700000000900, seq: 9, deviceId: "dev_b", opId: "op_9" },
      },
      clientUpdatedAt: 1700000000900,
      updatedAt: serverTimestamp(),
    })
  );

  // Unknown fieldStamps key rejected
  await assertFails(
    plRef.update({
      fieldStamps: {
        unknownKey: { timestampMs: 1700000001000, seq: 6, deviceId: "dev_a", opId: "op_6" },
      },
      updatedAt: serverTimestamp(),
    })
  );
});

test("Owner-only sync_meta/clock validates server timestamp and blocks cross-user or arbitrary docs", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const clockRef = aliceDb.collection("users").doc(ALICE_UID).collection("sync_meta").doc("clock");

  await assertSucceeds(clockRef.set({ serverTime: serverTimestamp() }));
  await assertSucceeds(clockRef.get());
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("sync_meta").doc("other").set({
      serverTime: serverTimestamp(),
    })
  );
  await assertFails(clockRef.set({ serverTime: serverTimestamp(), extra: "nope" }));
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("sync_meta").doc("clock").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("sync_meta").doc("clock").set({
    serverTime: serverTimestamp(),
  }));
  await assertSucceeds(clockRef.delete());
});

test("Playlist activeSnapshotId requires matching syncRevision and trackCount on create and every update, while preserving legacy playlists", async () => {
  const path = require("node:path");
  const rutDir = path.dirname(require.resolve("@firebase/rules-unit-testing"));
  const firebase = require(require.resolve("firebase/compat/app", { paths: [__dirname, rutDir] }));
  require(require.resolve("firebase/compat/firestore", { paths: [__dirname, rutDir] }));
  const { FieldValue } = firebase.firestore;

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const anonDb = testEnv.unauthenticatedContext().firestore();

  const plRef = aliceDb.collection("users").doc(ALICE_UID).collection("playlists").doc("pl_meta_strict");
  const snap1Ref = plRef.collection("snapshots").doc("snap_m1");
  const snap2Ref = plRef.collection("snapshots").doc("snap_m2");
  const snapStagingRef = plRef.collection("snapshots").doc("snap_m_stg");
  const snapDeletingRef = plRef.collection("snapshots").doc("snap_m_del");

  // Prepare STAGING, COMMITTED (rev 2, count 3 and rev 3, count 5), and DELETING snapshots
  await assertSucceeds(
    snap1Ref.set({
      state: "STAGING",
      syncRevision: 2,
      trackCount: 3,
      clientUpdatedAt: 1700000000000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(snap1Ref.update({ state: "COMMITTED", updatedAt: serverTimestamp() }));

  await assertSucceeds(
    snap2Ref.set({
      state: "STAGING",
      syncRevision: 3,
      trackCount: 5,
      clientUpdatedAt: 1700000001000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(snap2Ref.update({ state: "COMMITTED", updatedAt: serverTimestamp() }));

  await assertSucceeds(
    snapStagingRef.set({
      state: "STAGING",
      syncRevision: 4,
      trackCount: 1,
      clientUpdatedAt: 1700000002000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  await assertSucceeds(
    snapDeletingRef.set({
      state: "STAGING",
      syncRevision: 5,
      trackCount: 1,
      clientUpdatedAt: 1700000002500,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(snapDeletingRef.update({ state: "DELETING", updatedAt: serverTimestamp() }));

  // 1. Creating with activeSnapshotId missing syncRevision must fail
  const baseNoRev = validPlaylistPayload({ activeSnapshotId: "snap_m1", trackCount: 3 });
  delete baseNoRev.syncRevision;
  await assertFails(plRef.set(baseNoRev));

  // 2. Creating with activeSnapshotId missing trackCount must fail
  const baseNoCount = validPlaylistPayload({ activeSnapshotId: "snap_m1", syncRevision: 2 });
  delete baseNoCount.trackCount;
  await assertFails(plRef.set(baseNoCount));

  // 3. Creating with activeSnapshotId mismatched syncRevision or trackCount must fail
  await assertFails(
    plRef.set(validPlaylistPayload({ activeSnapshotId: "snap_m1", syncRevision: 99, trackCount: 3 }))
  );
  await assertFails(
    plRef.set(validPlaylistPayload({ activeSnapshotId: "snap_m1", syncRevision: 2, trackCount: 99 }))
  );

  // 4. Creating pointing to missing, STAGING, or DELETING snapshot must fail
  await assertFails(
    plRef.set(validPlaylistPayload({ activeSnapshotId: "snap_missing", syncRevision: 2, trackCount: 3 }))
  );
  await assertFails(
    plRef.set(validPlaylistPayload({ activeSnapshotId: "snap_m_stg", syncRevision: 4, trackCount: 1 }))
  );
  await assertFails(
    plRef.set(validPlaylistPayload({ activeSnapshotId: "snap_m_del", syncRevision: 5, trackCount: 1 }))
  );

  // Valid create with matching COMMITTED snapshot metadata succeeds
  await assertSucceeds(
    plRef.set(validPlaylistPayload({ activeSnapshotId: "snap_m1", syncRevision: 2, trackCount: 3 }))
  );

  // Cross-user and unauthenticated reads/writes remain denied
  const bobPlRef = bobDb.collection("users").doc(ALICE_UID).collection("playlists").doc("pl_meta_strict");
  const anonPlRef = anonDb.collection("users").doc(ALICE_UID).collection("playlists").doc("pl_meta_strict");
  await assertFails(bobPlRef.get());
  await assertFails(anonPlRef.get());
  await assertFails(bobPlRef.update({ title: "Hijacked", updatedAt: serverTimestamp() }));
  await assertFails(anonPlRef.update({ title: "Hijacked", updatedAt: serverTimestamp() }));

  // 5. Same-activeSnapshotId update changing syncRevision or trackCount without rotating snapshot must fail
  await assertFails(
    plRef.update({
      syncRevision: 3,
      clientUpdatedAt: 1700000003000,
      updatedAt: serverTimestamp(),
    })
  );
  await assertFails(
    plRef.update({
      trackCount: 4,
      clientUpdatedAt: 1700000003000,
      updatedAt: serverTimestamp(),
    })
  );

  // 6. Deleting syncRevision or trackCount while retaining non-null activeSnapshotId must fail
  await assertFails(
    plRef.update({
      syncRevision: FieldValue.delete(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertFails(
    plRef.update({
      trackCount: FieldValue.delete(),
      updatedAt: serverTimestamp(),
    })
  );

  // 7. Same-activeSnapshotId metadata update succeeds when syncRevision and trackCount stay consistent
  await assertSucceeds(
    plRef.update({
      title: "Metadata Updated Same Snapshot",
      syncRevision: 2,
      trackCount: 3,
      clientUpdatedAt: 1700000003500,
      updatedAt: serverTimestamp(),
    })
  );

  // 8. Rotating to a different COMMITTED snapshot with mismatched metadata fails, matching metadata succeeds
  await assertFails(
    plRef.update({
      activeSnapshotId: "snap_m2",
      syncRevision: 3,
      trackCount: 99,
      clientUpdatedAt: 1700000004000,
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(
    plRef.update({
      activeSnapshotId: "snap_m2",
      syncRevision: 3,
      trackCount: 5,
      clientUpdatedAt: 1700000004500,
      updatedAt: serverTimestamp(),
    })
  );

  // 9. Legacy playlist without activeSnapshotId (or activeSnapshotId == null) remains readable & updateable
  const legacyNoSnapRef = aliceDb.collection("users").doc(ALICE_UID).collection("playlists").doc("pl_legacy_nosnap");
  await assertSucceeds(
    legacyNoSnapRef.set(validPlaylistPayload({ title: "Legacy Playlist", syncRevision: 1 }))
  );
  await assertSucceeds(legacyNoSnapRef.get());
  await assertSucceeds(
    legacyNoSnapRef.update({
      title: "Legacy Updated Title",
      syncRevision: 1,
      activeSnapshotId: null,
      clientUpdatedAt: 1700000005000,
      updatedAt: serverTimestamp(),
    })
  );

  // 10. Maximal real-world update with activeSnapshotId rotation + all causal metadata + coverUri succeeds cleanly
  const snapMaxRef = plRef.collection("snapshots").doc("snap_m3_max");
  await assertSucceeds(
    snapMaxRef.set({
      state: "STAGING",
      syncRevision: 4,
      trackCount: 12,
      clientUpdatedAt: 1700000006000,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(snapMaxRef.update({ state: "COMMITTED", updatedAt: serverTimestamp() }));
  await assertSucceeds(
    plRef.update({
      title: "Maximal Snapshot Rotate",
      description: "Full causal payload",
      coverUri: "https://cdn.example.com/max.jpg",
      coverRemoved: false,
      syncRevision: 4,
      activeSnapshotId: "snap_m3_max",
      trackCount: 12,
      lastDeviceId: "dev_max",
      lastOpSeq: 42,
      lastOpId: "op_42",
      fieldStamps: {
        title: { timestampMs: 1700000005100, seq: 39, deviceId: "dev_max", opId: "op_39" },
        description: { timestampMs: 1700000005200, seq: 40, deviceId: "dev_max", opId: "op_40" },
        cover: { timestampMs: 1700000005300, seq: 41, deviceId: "dev_max", opId: "op_41" },
        order: { timestampMs: 1700000005400, seq: 42, deviceId: "dev_max", opId: "op_42" },
      },
      trackTombstones: {
        trk_old_1: { timestampMs: 1700000005000, seq: 37, deviceId: "dev_max", opId: "op_37" },
        trk_old_2: { timestampMs: 1700000005050, seq: 38, deviceId: "dev_max", opId: "op_38" },
      },
      clientUpdatedAt: 1700000006000,
      updatedAt: serverTimestamp(),
    })
  );
});




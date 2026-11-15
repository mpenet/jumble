// Turns browser notifications on or off for this device, from the settings
// page: registers the service worker, subscribes with the community's key.

const section = document.getElementById("push");

function show(name, on) {
  const el = section.querySelector(`[data-push-${name}]`);
  if (el) el.hidden = !on;
}

// The service worker's address is the one script URL this page creates,
// through a Trusted Types policy of its own.
const policy = window.trustedTypes
  ? window.trustedTypes.createPolicy("jumble-push", {
      createScriptURL: (url) => {
        if (url !== "/sw.js") throw new TypeError("Unexpected script: " + url);
        return url;
      },
    })
  : null;

async function digest(text) {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(bytes), (b) => b.toString(16).padStart(2, "0")).join("");
}

function keyBytes(base64url) {
  const base64 = base64url.replace(/-/g, "+").replace(/_/g, "/");
  return Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
}

function post(url, body) {
  return fetch(url, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  }).then((response) => {
    if (!response.ok) throw new Error(response.status);
  });
}

function sameBytes(a, b) {
  return a.length === b.length && a.every((x, i) => x === b[i]);
}

// Shows a failure instead of leaving the click unanswered.
function failing(action) {
  return () =>
    action().catch((error) => {
      console.error("Notifications:", error);
      show("failed", true);
    });
}

async function setUp(section) {
  if (!("serviceWorker" in navigator && "PushManager" in window && "Notification" in window)) {
    show("unsupported", true);
    return;
  }
  const registration = await navigator.serviceWorker.register(policy ? policy.createScriptURL("/sw.js") : "/sw.js");
  await navigator.serviceWorker.ready;
  const key = keyBytes(section.dataset.applicationKey);
  const existing = await registration.pushManager.getSubscription();
  // One made with another key than the community's cannot be pushed to.
  const current =
    existing && existing.options.applicationServerKey &&
    sameBytes(new Uint8Array(existing.options.applicationServerKey), key)
      ? existing
      : null;
  const known = current && section.querySelector(`[data-device="${await digest(current.endpoint)}"]`);
  if (known) known.querySelector("[data-push-this]").hidden = false;
  show("on", !known);
  show("off", Boolean(known));
  if (Notification.permission === "denied") show("blocked", true);

  section.querySelector("[data-push-on]").addEventListener(
    "click",
    failing(async () => {
      if ((await Notification.requestPermission()) !== "granted") {
        show("blocked", true);
        return;
      }
      if (existing && !current) await existing.unsubscribe();
      const subscription =
        current || (await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key }));
      await post("/push/subscriptions", subscription.toJSON());
      location.reload();
    }),
  );

  section.querySelector("[data-push-off]").addEventListener(
    "click",
    failing(async () => {
      await post("/push/subscriptions/delete", { endpoint: current.endpoint });
      await current.unsubscribe();
      location.reload();
    }),
  );
}

if (section)
  setUp(section).catch((error) => {
    console.error("Notifications unavailable:", error);
    show("unsupported", true);
  });

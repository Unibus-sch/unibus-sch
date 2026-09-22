import { api } from "../services/api";

function base64UrlToUint8Array(base64Url: string) {
  const padding = "=".repeat((4 - (base64Url.length % 4)) % 4);
  const base64 = (base64Url + padding).replace(/-/g, "+").replace(/_/g, "/");
  const raw = window.atob(base64);
  return Uint8Array.from([...raw].map((char) => char.charCodeAt(0)));
}

export function isPushSupported() {
  return "serviceWorker" in navigator && "PushManager" in window && "Notification" in window;
}

function waitForServiceWorkerReady() {
  return Promise.race([
    navigator.serviceWorker.ready,
    new Promise<never>((_, reject) => {
      window.setTimeout(() => {
        reject(new Error("서비스워커가 아직 준비되지 않았습니다. 배포된 주소에서 새로고침 후 다시 시도해 주세요."));
      }, 7000);
    }),
  ]);
}

async function getVapidPublicKey() {
  return api.getVapidPublicKey();
}

export async function ensurePushSubscription() {
  if (!isPushSupported()) {
    throw new Error("이 브라우저에서는 백그라운드 푸시 알림을 지원하지 않습니다.");
  }

  const registration = await waitForServiceWorkerReady();
  const publicKey = await getVapidPublicKey();
  let subscription = await registration.pushManager.getSubscription();

  if (!subscription) {
    subscription = await registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: base64UrlToUint8Array(publicKey),
    });
  }

  await api.subscribePush(subscription.toJSON());
  return subscription;
}

export async function disablePushSubscription() {
  if (!isPushSupported()) return;

  const registration = await waitForServiceWorkerReady();
  const subscription = await registration.pushManager.getSubscription();

  if (!subscription) return;

  await api.unsubscribePush(subscription.endpoint).catch(() => {});
  await subscription.unsubscribe().catch(() => {});
}

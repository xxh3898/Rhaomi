import type { JsonValidator } from "./types";

const BASE64URL_PATTERN = /^[A-Za-z0-9_-]+$/;
type WebAuthnTransport =
  | AuthenticatorTransport
  | "smart-card";

const AUTHENTICATOR_TRANSPORTS: readonly WebAuthnTransport[] = [
  "ble",
  "hybrid",
  "internal",
  "nfc",
  "smart-card",
  "usb",
];

type CredentialDescriptorJson = Readonly<{
  type: "public-key";
  id: string;
  transports: readonly WebAuthnTransport[];
}>;

export type RegistrationOptionsJson = Readonly<{
  rp: Readonly<{ id: string; name: string }>;
  user: Readonly<{ id: string; name: string; displayName: string }>;
  challenge: string;
  pubKeyCredParams: readonly Readonly<{
    type: "public-key";
    alg: number;
  }>[];
  timeout: number;
  excludeCredentials: readonly CredentialDescriptorJson[];
  authenticatorSelection: Readonly<{
    authenticatorAttachment: AuthenticatorAttachment | null;
    residentKey: ResidentKeyRequirement;
    userVerification: UserVerificationRequirement;
  }>;
  attestation: AttestationConveyancePreference;
  extensions: Readonly<{ credProps: boolean }>;
}>;

export type AuthenticationOptionsJson = Readonly<{
  challenge: string;
  timeout: number;
  rpId: string;
  allowCredentials: readonly CredentialDescriptorJson[];
  userVerification: UserVerificationRequirement;
}>;

type RegistrationResultJson = Readonly<{
  id: string;
  rawId: string;
  type: "public-key";
  authenticatorAttachment: AuthenticatorAttachment | null;
  clientExtensionResults: Readonly<{
    credProps?: Readonly<{ rk: boolean }>;
  }>;
  response: Readonly<{
    clientDataJSON: string;
    attestationObject: string;
    transports: readonly WebAuthnTransport[];
  }>;
  label: string;
}>;

type AuthenticationResultJson = Readonly<{
  id: string;
  rawId: string;
  type: "public-key";
  authenticatorAttachment: AuthenticatorAttachment | null;
  clientExtensionResults: Readonly<{
    credProps?: Readonly<{ rk: boolean }>;
  }>;
  response: Readonly<{
    clientDataJSON: string;
    authenticatorData: string;
    signature: string;
    userHandle: string | null;
  }>;
}>;

type CredentialProvider = Pick<CredentialsContainer, "create" | "get">;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isCanonicalBase64Url(value: unknown): value is string {
  if (
    typeof value !== "string" ||
    value.length === 0 ||
    !BASE64URL_PATTERN.test(value)
  ) {
    return false;
  }
  try {
    return encodeBase64Url(decodeBase64Url(value)) === value;
  } catch {
    return false;
  }
}

function isDescriptor(value: unknown): value is CredentialDescriptorJson {
  return (
    isRecord(value) &&
    value.type === "public-key" &&
    isCanonicalBase64Url(value.id) &&
    Array.isArray(value.transports) &&
    value.transports.every(isAuthenticatorTransport)
  );
}

export const isRegistrationOptions: JsonValidator<RegistrationOptionsJson> = (
  value,
): value is RegistrationOptionsJson => {
  if (!isRecord(value)) {
    return false;
  }
  const { rp, user, authenticatorSelection, extensions } = value;
  return (
    isRecord(rp) &&
    typeof rp.id === "string" &&
    rp.id.length > 0 &&
    typeof rp.name === "string" &&
    rp.name.length > 0 &&
    isRecord(user) &&
    isCanonicalBase64Url(user.id) &&
    typeof user.name === "string" &&
    typeof user.displayName === "string" &&
    isCanonicalBase64Url(value.challenge) &&
    decodeBase64Url(value.challenge).byteLength >= 32 &&
    Array.isArray(value.pubKeyCredParams) &&
    value.pubKeyCredParams.length > 0 &&
    value.pubKeyCredParams.every(
      (parameter) =>
        isRecord(parameter) &&
        parameter.type === "public-key" &&
        Number.isSafeInteger(parameter.alg),
    ) &&
    typeof value.timeout === "number" &&
    Number.isSafeInteger(value.timeout) &&
    value.timeout > 0 &&
    Array.isArray(value.excludeCredentials) &&
    value.excludeCredentials.every(isDescriptor) &&
    isRecord(authenticatorSelection) &&
    (authenticatorSelection.authenticatorAttachment === null ||
      authenticatorSelection.authenticatorAttachment === "platform" ||
      authenticatorSelection.authenticatorAttachment === "cross-platform") &&
    authenticatorSelection.residentKey === "required" &&
    authenticatorSelection.userVerification === "required" &&
    value.attestation === "none" &&
    isRecord(extensions) &&
    extensions.credProps === true
  );
};

export const isAuthenticationOptions: JsonValidator<AuthenticationOptionsJson> = (
  value,
): value is AuthenticationOptionsJson =>
  isRecord(value) &&
  isCanonicalBase64Url(value.challenge) &&
  decodeBase64Url(value.challenge).byteLength >= 32 &&
  typeof value.timeout === "number" &&
  Number.isSafeInteger(value.timeout) &&
  value.timeout > 0 &&
  typeof value.rpId === "string" &&
  value.rpId.length > 0 &&
  Array.isArray(value.allowCredentials) &&
  value.allowCredentials.length > 0 &&
  value.allowCredentials.every(isDescriptor) &&
  value.userVerification === "required";

export async function createPasskey(
  options: RegistrationOptionsJson,
  label: string,
  provider: CredentialProvider,
): Promise<RegistrationResultJson> {
  const credential = await provider.create({
    publicKey: {
      rp: { ...options.rp },
      challenge: decodeBase64Url(options.challenge),
      user: { ...options.user, id: decodeBase64Url(options.user.id) },
      pubKeyCredParams: options.pubKeyCredParams.map((parameter) => ({
        ...parameter,
      })),
      timeout: options.timeout,
      excludeCredentials: options.excludeCredentials.map(descriptor),
      authenticatorSelection: {
        ...options.authenticatorSelection,
        authenticatorAttachment:
          options.authenticatorSelection.authenticatorAttachment ?? undefined,
      },
      attestation: options.attestation,
      extensions: { ...options.extensions },
    },
  });
  if (!isPublicKeyCredentialLike(credential)) {
    throw new Error("WEBAUTHN_CREDENTIAL_INVALID");
  }
  const response = credential.response;
  if (!isAttestationResponseLike(response)) {
    throw new Error("WEBAUTHN_CREDENTIAL_INVALID");
  }
  return {
    ...credentialBase(credential),
    clientExtensionResults: clientExtensions(credential),
    response: {
      clientDataJSON: encodeBase64Url(response.clientDataJSON),
      attestationObject: encodeBase64Url(response.attestationObject),
      transports:
        typeof response.getTransports === "function"
          ? response.getTransports().filter(isAuthenticatorTransport)
          : [],
    },
    label,
  };
}

export async function getPasskeyAssertion(
  options: AuthenticationOptionsJson,
  provider: CredentialProvider,
): Promise<AuthenticationResultJson> {
  const credential = await provider.get({
    publicKey: {
      ...options,
      challenge: decodeBase64Url(options.challenge),
      allowCredentials: options.allowCredentials.map(descriptor),
    },
  });
  if (!isPublicKeyCredentialLike(credential)) {
    throw new Error("WEBAUTHN_CREDENTIAL_INVALID");
  }
  const response = credential.response;
  if (!isAssertionResponseLike(response)) {
    throw new Error("WEBAUTHN_CREDENTIAL_INVALID");
  }
  return {
    ...credentialBase(credential),
    clientExtensionResults: clientExtensions(credential),
    response: {
      clientDataJSON: encodeBase64Url(response.clientDataJSON),
      authenticatorData: encodeBase64Url(response.authenticatorData),
      signature: encodeBase64Url(response.signature),
      userHandle:
        response.userHandle === null
          ? null
          : encodeBase64Url(response.userHandle),
    },
  };
}

export function browserCredentialProvider(): CredentialProvider {
  if (
    typeof window === "undefined" ||
    !window.isSecureContext ||
    !window.PublicKeyCredential ||
    !navigator.credentials
  ) {
    throw new Error("WEBAUTHN_UNSUPPORTED");
  }
  return navigator.credentials;
}

function descriptor(value: CredentialDescriptorJson): PublicKeyCredentialDescriptor {
  return {
    type: "public-key",
    id: decodeBase64Url(value.id),
    transports: value.transports.filter(
      (transport): transport is AuthenticatorTransport =>
        transport !== "smart-card",
    ),
  };
}

function credentialBase(credential: PublicKeyCredential): Readonly<{
  id: string;
  rawId: string;
  type: "public-key";
  authenticatorAttachment: AuthenticatorAttachment | null;
}> {
  const rawId = encodeBase64Url(credential.rawId);
  if (credential.id !== rawId || credential.type !== "public-key") {
    throw new Error("WEBAUTHN_CREDENTIAL_INVALID");
  }
  return {
    id: credential.id,
    rawId,
    type: "public-key" as const,
    authenticatorAttachment: (
      credential.authenticatorAttachment === "platform" ||
      credential.authenticatorAttachment === "cross-platform"
        ? credential.authenticatorAttachment
        : null
    ) as AuthenticatorAttachment | null,
  };
}

function clientExtensions(credential: PublicKeyCredential) {
  const values = credential.getClientExtensionResults();
  if (isRecord(values.credProps) && typeof values.credProps.rk === "boolean") {
    return { credProps: { rk: values.credProps.rk } };
  }
  return {};
}

function isPublicKeyCredentialLike(value: unknown): value is PublicKeyCredential {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    value.type === "public-key" &&
    isBufferSource(value.rawId) &&
    isRecord(value.response) &&
    typeof value.getClientExtensionResults === "function"
  );
}

function isAttestationResponseLike(
  value: unknown,
): value is AuthenticatorAttestationResponse {
  return (
    isRecord(value) &&
    isBufferSource(value.clientDataJSON) &&
    isBufferSource(value.attestationObject)
  );
}

function isAssertionResponseLike(
  value: unknown,
): value is AuthenticatorAssertionResponse {
  return (
    isRecord(value) &&
    isBufferSource(value.clientDataJSON) &&
    isBufferSource(value.authenticatorData) &&
    isBufferSource(value.signature) &&
    (value.userHandle === null || isBufferSource(value.userHandle))
  );
}

function isBufferSource(value: unknown): value is BufferSource {
  return value instanceof ArrayBuffer || ArrayBuffer.isView(value);
}

function isAuthenticatorTransport(value: unknown): value is WebAuthnTransport {
  return (
    typeof value === "string" &&
    AUTHENTICATOR_TRANSPORTS.includes(value as WebAuthnTransport)
  );
}

export function decodeBase64Url(value: string): ArrayBuffer {
  if (!BASE64URL_PATTERN.test(value)) {
    throw new Error("BASE64URL_INVALID");
  }
  const padding = "=".repeat((4 - (value.length % 4)) % 4);
  const decoded = atob(value.replaceAll("-", "+").replaceAll("_", "/") + padding);
  const bytes = Uint8Array.from(decoded, (character) => character.charCodeAt(0));
  const result = bytes.buffer.slice(
    bytes.byteOffset,
    bytes.byteOffset + bytes.byteLength,
  ) as ArrayBuffer;
  if (encodeBase64Url(result) !== value) {
    throw new Error("BASE64URL_INVALID");
  }
  return result;
}

export function encodeBase64Url(value: BufferSource): string {
  const bytes = new Uint8Array(
    value instanceof ArrayBuffer
      ? value
      : value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength),
  );
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary)
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replace(/=+$/u, "");
}

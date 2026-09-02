import { describe, expect, it, vi } from "vitest";

import {
  createPasskey,
  decodeBase64Url,
  encodeBase64Url,
  getPasskeyAssertion,
  isAuthenticationOptions,
  isRegistrationOptions,
} from "./webauthn";

const bytes = (...values: number[]) => new Uint8Array(values).buffer;
const challenge = encodeBase64Url(new Uint8Array(32).fill(7));
const credentialId = encodeBase64Url(bytes(1, 2, 3, 4));

const registrationOptions = {
  rp: { id: "admin.example.test", name: "Rhaomi Admin" },
  user: { id: credentialId, name: "admin@example.test", displayName: "관리자" },
  challenge,
  pubKeyCredParams: [{ type: "public-key", alg: -7 }],
  timeout: 300_000,
  excludeCredentials: [],
  authenticatorSelection: {
    authenticatorAttachment: null,
    residentKey: "required",
    userVerification: "required",
  },
  attestation: "none",
  extensions: { credProps: true },
} as const;

const authenticationOptions = {
  challenge,
  timeout: 300_000,
  rpId: "admin.example.test",
  allowCredentials: [
    { type: "public-key", id: credentialId, transports: ["internal"] },
  ],
  userVerification: "required",
} as const;

describe("admin WebAuthn browser codec", () => {
  it("canonical base64url만 lossless ArrayBuffer로 변환한다", () => {
    expect(encodeBase64Url(decodeBase64Url("AQIDBA"))).toBe("AQIDBA");
    expect(() => decodeBase64Url("AQIDBA==")).toThrow();
    expect(() => decodeBase64Url("AQIDBA+/")).toThrow();
    expect(() => decodeBase64Url("")).toThrow();
  });

  it("registration options의 32-byte challenge와 UV required 계약을 검증한다", () => {
    expect(isRegistrationOptions(registrationOptions)).toBe(true);
    expect(
      isRegistrationOptions({ ...registrationOptions, challenge: "AQ" }),
    ).toBe(false);
    expect(
      isRegistrationOptions({
        ...registrationOptions,
        authenticatorSelection: {
          ...registrationOptions.authenticatorSelection,
          userVerification: "preferred",
        },
      }),
    ).toBe(false);
    expect(isAuthenticationOptions(authenticationOptions)).toBe(true);
  });

  it("registration binary를 canonical base64url JSON으로만 직렬화한다", async () => {
    const rawId = decodeBase64Url(credentialId);
    const provider = {
      create: vi.fn().mockResolvedValue({
        id: credentialId,
        rawId,
        type: "public-key",
        authenticatorAttachment: "platform",
        response: {
          clientDataJSON: bytes(5, 6),
          attestationObject: bytes(7, 8),
          getTransports: () => ["internal"],
        },
        getClientExtensionResults: () => ({ credProps: { rk: true } }),
      }),
      get: vi.fn(),
    };

    const result = await createPasskey(
      registrationOptions,
      "Mac mini Passkey",
      provider,
    );

    expect(provider.create).toHaveBeenCalledOnce();
    const createArgument = provider.create.mock.calls[0][0];
    expect(createArgument.publicKey.challenge).toBeInstanceOf(ArrayBuffer);
    expect(createArgument.publicKey.user.id).toBeInstanceOf(ArrayBuffer);
    expect(result).toEqual({
      id: credentialId,
      rawId: credentialId,
      type: "public-key",
      authenticatorAttachment: "platform",
      clientExtensionResults: { credProps: { rk: true } },
      response: {
        clientDataJSON: "BQY",
        attestationObject: "Bwg",
        transports: ["internal"],
      },
      label: "Mac mini Passkey",
    });
  });

  it("assertion을 자동 재전송하지 않고 한 번만 navigator에 요청한다", async () => {
    const provider = {
      create: vi.fn(),
      get: vi.fn().mockResolvedValue({
        id: credentialId,
        rawId: decodeBase64Url(credentialId),
        type: "public-key",
        authenticatorAttachment: "platform",
        response: {
          clientDataJSON: bytes(1),
          authenticatorData: bytes(2),
          signature: bytes(3),
          userHandle: null,
        },
        getClientExtensionResults: () => ({}),
      }),
    };

    const result = await getPasskeyAssertion(authenticationOptions, provider);

    expect(provider.get).toHaveBeenCalledOnce();
    expect(result.response).toEqual({
      clientDataJSON: "AQ",
      authenticatorData: "Ag",
      signature: "Aw",
      userHandle: null,
    });
  });
});

/**
 * ClassId encode/decode, isomorphic (browser + node) so it can run in the page
 * component and in unit tests without {@code Buffer}.
 *
 * The classId is the agent's {@code LoadedClassRepository.classId} form:
 * {@code base64url(classLoaderId + "|" + binaryClassName)}. It unambiguously locates a
 * class by <em>both</em> {@code binaryClassName} and {@code classLoaderId}; a bare class
 * name is never accepted. Two classes with the same name on different loaders produce
 * different classIds - this is the ClassLoader-isolation field the UI must surface.
 */

export type DecodedClassId = {
  classLoaderId: string;
  binaryClassName: string;
};

function base64UrlToBytes(base64url: string): Uint8Array {
  const base64 = base64url.replace(/-/g, "+").replace(/_/g, "/");
  const padded = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function bytesToBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i += 1) binary += String.fromCharCode(bytes[i]);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Decode a classId into its {@code (classLoaderId, binaryClassName)} pair, or {@code null} if malformed. */
export function decodeClassId(classId: string): DecodedClassId | null {
  if (!classId) return null;
  let decoded: string;
  try {
    decoded = new TextDecoder("utf-8").decode(base64UrlToBytes(classId));
  } catch {
    return null;
  }
  const separator = decoded.indexOf("|");
  if (separator <= 0) return null;
  const classLoaderId = decoded.slice(0, separator);
  const binaryClassName = decoded.slice(separator + 1);
  if (!classLoaderId || !binaryClassName) return null;
  return { classLoaderId, binaryClassName };
}

/** Encode a {@code (binaryClassName, classLoaderId)} pair back into a classId. */
export function encodeClassId(binaryClassName: string, classLoaderId: string): string {
  const bytes = new TextEncoder().encode(`${classLoaderId}|${binaryClassName}`);
  return bytesToBase64Url(bytes);
}

/** Human-readable identity for display, e.g. {@code com.example.Foo @ loader-7f3a}. */
export function describeClassId(classId: string): string {
  const decoded = decodeClassId(classId);
  if (!decoded) return classId || "(无效 classId)";
  return `${decoded.binaryClassName} @ ${decoded.classLoaderId}`;
}

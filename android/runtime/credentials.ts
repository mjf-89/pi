import type { AuthOperationOptions, Credential, CredentialInfo, CredentialStore } from "@earendil-works/pi-ai";

export interface CredentialHost {
  readCredential(): string;
  writeCredential(value: string): void;
}

/** One process owns the Android runtime. Persist refreshes before releasing the lock. */
export class AndroidCredentialStore implements CredentialStore {
  private tail: Promise<unknown> = Promise.resolve();
  constructor(private readonly host: CredentialHost) {}

  async read(providerId: string, options?: AuthOperationOptions): Promise<Credential | undefined> {
    options?.signal?.throwIfAborted();
    if (providerId !== "openai") return undefined;
    const value = this.host.readCredential();
    return value ? JSON.parse(value) as Credential : undefined;
  }
  async list(options?: AuthOperationOptions): Promise<readonly CredentialInfo[]> {
    const value = await this.read("openai", options);
    return value ? [{ providerId: "openai", type: value.type }] : [];
  }
  private serial<T>(operation: () => Promise<T>): Promise<T> {
    const result = this.tail.then(operation);
    this.tail = result.catch(() => undefined);
    return result;
  }
  modify(providerId: string, fn: (value: Credential | undefined) => Promise<Credential | undefined>, options?: AuthOperationOptions): Promise<Credential | undefined> {
    return this.serial(async () => {
      if (providerId !== "openai") throw new Error("Only OpenAI subscription credentials are supported.");
      const current = await this.read(providerId, options);
      const next = await fn(current);
      options?.signal?.throwIfAborted();
      if (next !== undefined) this.host.writeCredential(JSON.stringify(next));
      return next ?? current;
    });
  }
  delete(providerId: string, options?: AuthOperationOptions): Promise<void> {
    return this.serial(async () => {
      options?.signal?.throwIfAborted();
      if (providerId === "openai") this.host.writeCredential("");
    });
  }
}

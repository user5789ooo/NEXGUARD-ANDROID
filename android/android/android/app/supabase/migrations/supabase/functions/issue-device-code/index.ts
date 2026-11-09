import { createClient } from "npm:@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  const jsonResponse = (data: unknown, status = 200) =>
    new Response(JSON.stringify(data), {
      status,
      headers: {
        ...corsHeaders,
        "Content-Type": "application/json",
      },
    });

  if (req.method !== "POST") {
    return jsonResponse({ error: "Method not allowed" }, 405);
  }

  try {
    const supabaseUrl = Deno.env.get("SUPABASE_URL");
    const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");

    if (!supabaseUrl || !serviceRoleKey) {
      return jsonResponse({ error: "Server configuration error" }, 500);
    }

    const authorization = req.headers.get("Authorization");

    if (!authorization?.startsWith("Bearer ")) {
      return jsonResponse({ error: "Authentication required" }, 401);
    }

    const accessToken = authorization.substring(7);

    const supabase = createClient(
      supabaseUrl,
      serviceRoleKey,
      {
        auth: {
          persistSession: false,
          autoRefreshToken: false,
        },
      },
    );

    const {
      data: { user },
      error: authError,
    } = await supabase.auth.getUser(accessToken);

    if (authError || !user) {
      return jsonResponse({ error: "Invalid session" }, 401);
    }

    let deviceName = "Android Device";

    try {
      const body = await req.json();

      if (typeof body.device_name === "string") {
        deviceName = body.device_name.trim().slice(0, 100) ||
          "Android Device";
      }
    } catch {
      return jsonResponse({ error: "Invalid request body" }, 400);
    }

    // Generate a cryptographically secure 8-digit code.
    const randomBytes = new Uint32Array(1);
    crypto.getRandomValues(randomBytes);

    const code = String(
      randomBytes[0] % 100000000,
    ).padStart(8, "0");

    // Store only the SHA-256 hash, never the plain code.
    const encoded = new TextEncoder().encode(code);
    const hashBuffer = await crypto.subtle.digest("SHA-256", encoded);

    const codeHash = Array.from(new Uint8Array(hashBuffer))
      .map((byte) => byte.toString(16).padStart(2, "0"))
      .join("");

    const expiresAt = new Date(
      Date.now() + 10 * 60 * 1000,
    ).toISOString();

    const { error: insertError } = await supabase
      .from("device_pairing_codes")
      .insert({
        owner_id: user.id,
        code_hash: codeHash,
        device_name: deviceName,
        expires_at: expiresAt,
      });

    if (insertError) {
      return jsonResponse({ error: "Could not create pairing code" }, 500);
    }

    return jsonResponse({
      code,
      expires_at: expiresAt,
      expires_in_seconds: 600,
      single_use: true,
    });
  } catch {
    return jsonResponse({ error: "Internal server error" }, 500);
  }
});

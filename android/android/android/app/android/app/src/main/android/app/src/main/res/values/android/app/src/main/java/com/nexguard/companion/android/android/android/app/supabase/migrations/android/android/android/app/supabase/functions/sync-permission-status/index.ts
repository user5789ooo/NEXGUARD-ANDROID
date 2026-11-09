
import { createClient } from "npm:@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const allowedPermissions = new Set([
  "contacts",
  "microphone",
  "location",
  "notifications",
]);

const allowedStatuses = new Set([
  "allowed",
  "denied",
  "not_requested",
  "not_available",
]);

Deno.serve(async (req: Request) => {
  const respond = (data: unknown, status = 200) =>
    new Response(JSON.stringify(data), {
      status,
      headers: {
        ...corsHeaders,
        "Content-Type": "application/json",
      },
    });

  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  if (req.method !== "POST") {
    return respond({ error: "Method not allowed" }, 405);
  }

  try {
    const url = Deno.env.get("SUPABASE_URL");
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");

    if (!url || !serviceKey) {
      return respond({ error: "Server configuration error" }, 500);
    }

    const authorization = req.headers.get("Authorization");

    if (!authorization?.startsWith("Bearer ")) {
      return respond({ error: "Authentication required" }, 401);
    }

    const supabase = createClient(url, serviceKey, {
      auth: {
        persistSession: false,
        autoRefreshToken: false,
      },
    });

    const token = authorization.substring(7);

    const {
      data: { user },
      error: authError,
    } = await supabase.auth.getUser(token);

    if (authError || !user) {
      return respond({ error: "Invalid session" }, 401);
    }

    const body = await req.json();

    const deviceId = String(body.device_id ?? "");
    const email = String(body.email ?? "").trim();
    const deviceName = String(
      body.device_name ?? "Android Device"
    ).trim();

    const permissions = body.permissions;

    if (
      !/^[0-9a-f-]{36}$/i.test(deviceId) ||
      !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) ||
      !Array.isArray(permissions) ||
      permissions.length < 1 ||
      permissions.length > 10
    ) {
      return respond({ error: "Invalid request data" }, 400);
    }

    const rows = [];

    for (const item of permissions) {
      if (
        !allowedPermissions.has(item?.permission_name) ||
        !allowedStatuses.has(item?.permission_status)
      ) {
        return respond(
          { error: "Unsupported permission or status" },
          400
        );
      }

      rows.push({
        owner_id: user.id,
        device_id: deviceId,
        email: email.slice(0, 254),
        device_name: deviceName.slice(0, 100),
        permission_name: item.permission_name,
        permission_status: item.permission_status,
        updated_at: new Date().toISOString(),
      });
    }

    const { error } = await supabase
      .from("device_permissions")
      .upsert(rows, {
        onConflict: "device_id,permission_name",
      });

    if (error) {
      console.error(error.message);
      return respond(
        { error: "Could not save permission status" },
        500
      );
    }

    return respond({
      success: true,
      saved: rows.length,
    });

  } catch (error) {
    console.error(error);
    return respond({ error: "Invalid request" }, 400);
  }
});

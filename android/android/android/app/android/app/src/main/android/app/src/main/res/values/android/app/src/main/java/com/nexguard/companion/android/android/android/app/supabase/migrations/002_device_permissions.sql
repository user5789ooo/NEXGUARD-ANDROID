
CREATE TABLE IF NOT EXISTS public.device_permissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    device_id UUID NOT NULL,
    email TEXT NOT NULL,
    device_name TEXT NOT NULL,
    permission_name TEXT NOT NULL CHECK (
        permission_name IN (
            'contacts',
            'microphone',
            'location',
            'notifications'
        )
    ),
    permission_status TEXT NOT NULL CHECK (
        permission_status IN (
            'allowed',
            'denied',
            'not_requested',
            'not_available'
        )
    ),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (device_id, permission_name)
);

ALTER TABLE public.device_permissions ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.device_permissions FROM anon, authenticated;

GRANT SELECT ON public.device_permissions TO authenticated;

CREATE POLICY "Users can view own device permissions"
ON public.device_permissions
FOR SELECT
TO authenticated
USING (auth.uid() = owner_id);

CREATE INDEX device_permissions_owner_updated_idx
ON public.device_permissions(owner_id, updated_at DESC);

// API Vercel Serverless Function: api/rest/v1/[resource].js
// Emulação unificada do Supabase PostgREST para 'licenses' e 'replay_commands'

import { sql } from '@vercel/postgres';

export default async function handler(req, res) {
    const { resource } = req.query;

    // --- 1. COMPATIBILIDADE DA TABELA LICENSES ---
    if (resource === 'licenses') {
        let licenseKey = req.query.license_key;
        if (licenseKey && licenseKey.startsWith('eq.')) {
            licenseKey = licenseKey.substring(3);
        }

        let email = req.query.email;
        if (email && email.startsWith('eq.')) {
            email = email.substring(3);
        }

        if (licenseKey) licenseKey = decodeURIComponent(licenseKey).replace(/['"]/g, '');
        if (email) email = decodeURIComponent(email).replace(/['"]/g, '');

        if (req.method === 'GET') {
            try {
                let rows = [];
                if (licenseKey) {
                    const result = await sql`
                        SELECT * FROM licenses 
                        WHERE license_key = ${licenseKey.toUpperCase()} OR LOWER(email) = ${licenseKey.toLowerCase()}
                        ORDER BY is_active DESC, activated_at DESC NULLS LAST LIMIT 1
                    `;
                    rows = result.rows;
                } else if (email) {
                    const result = await sql`
                        SELECT * FROM licenses 
                        WHERE LOWER(email) = ${email.toLowerCase()}
                        ORDER BY is_active DESC, activated_at DESC NULLS LAST LIMIT 1
                    `;
                    rows = result.rows;
                } else {
                    const result = await sql`SELECT * FROM licenses LIMIT 50`;
                    rows = result.rows;
                }

                const mappedRows = rows.map(r => ({
                    ...r,
                    device_id: r.device_id || null
                }));

                return res.status(200).json(mappedRows);
            } catch (e) {
                console.error("Erro no GET compatibilidade:", e);
                return res.status(500).json({ error: e.message });
            }
        }

        if (req.method === 'PATCH') {
            if (!licenseKey) return res.status(400).json({ error: 'Chave da licenca nao informada' });
            const { device_id, activated_at, is_active } = req.body;

            try {
                if (is_active !== undefined) {
                    const isActiveBool = is_active === true;
                    await sql`
                        UPDATE licenses 
                        SET is_active = ${isActiveBool} 
                        WHERE license_key = ${licenseKey.toUpperCase()} OR LOWER(email) = ${licenseKey.toLowerCase()}
                    `;
                } else {
                    const finalDeviceId = device_id === undefined ? null : device_id;
                    const finalActivatedAt = activated_at || new Date().toISOString();
                    await sql`
                        UPDATE licenses 
                        SET device_id = ${finalDeviceId}, activated_at = ${finalActivatedAt} 
                        WHERE license_key = ${licenseKey.toUpperCase()} OR LOWER(email) = ${licenseKey.toLowerCase()}
                    `;
                }
                return res.status(200).json({ success: true });
            } catch (e) {
                console.error("Erro no PATCH compatibilidade:", e);
                return res.status(500).json({ error: e.message });
            }
        }
    }

    // --- 2. COMPATIBILIDADE DA TABELA REPLAY_COMMANDS ---
    if (resource === 'replay_commands') {
        let licenseKey = req.query.license_key;
        if (licenseKey && licenseKey.startsWith('eq.')) {
            licenseKey = licenseKey.substring(3);
        }
        if (licenseKey) {
            licenseKey = decodeURIComponent(licenseKey).replace(/['"]/g, '');
        }

        if (req.method === 'GET') {
            if (!licenseKey) return res.status(400).json({ error: 'Licenca nao fornecida' });
            try {
                const { rows } = await sql`
                    SELECT * FROM replay_commands 
                    WHERE license_key = ${licenseKey} 
                    ORDER BY created_at DESC 
                    LIMIT 1
                `;
                return res.status(200).json(rows);
            } catch (e) {
                console.error("Erro no GET replay_commands:", e);
                return res.status(500).json({ error: e.message });
            }
        }

        if (req.method === 'POST') {
            const { license_key, command } = req.body;
            if (!license_key || !command) return res.status(400).json({ error: 'Licenca e comando sao obrigatorios' });
            try {
                await sql`
                    INSERT INTO replay_commands (license_key, command) 
                    VALUES (${license_key.trim().toUpperCase()}, ${command.trim()})
                `;
                return res.status(201).json({ success: true });
            } catch (e) {
                console.error("Erro no POST replay_commands:", e);
                return res.status(500).json({ error: e.message });
            }
        }
    }

    return res.status(404).json({ error: 'Recurso não encontrado' });
}

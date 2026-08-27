// API Vercel Serverless Function: api/rest/v1/licenses.js
// Rota de compatibilidade do Supabase PostgREST para a tabela 'licenses'

import { sql } from '@vercel/postgres';

export default async function handler(req, res) {
    let licenseKey = req.query.license_key;
    if (licenseKey && licenseKey.startsWith('eq.')) {
        licenseKey = licenseKey.substring(3);
    }

    let email = req.query.email;
    if (email && email.startsWith('eq.')) {
        email = email.substring(3);
    }

    // Remover possíveis aspas simples ou duplas extras do decode do parametro
    if (licenseKey) licenseKey = decodeURIComponent(licenseKey).replace(/['"]/g, '');
    if (email) email = decodeURIComponent(email).replace(/['"]/g, '');

    // 1. Método GET - Consulta de Licença
    if (req.method === 'GET') {
        try {
            let rows = [];
            if (licenseKey) {
                const result = await sql`SELECT * FROM licenses WHERE license_key = ${licenseKey}`;
                rows = result.rows;
            } else if (email) {
                const result = await sql`SELECT * FROM licenses WHERE email = ${email}`;
                rows = result.rows;
            } else {
                const result = await sql`SELECT * FROM licenses LIMIT 50`;
                rows = result.rows;
            }

            // Mapeamento opcional para assegurar null no device_id se vazio
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

    // 2. Método PATCH - Atualização de Licença (Associação/Desassociação de device ou Desativação)
    if (req.method === 'PATCH') {
        if (!licenseKey) {
            return res.status(400).json({ error: 'Chave da licenca nao informada' });
        }

        const { device_id, activated_at, is_active } = req.body;

        try {
            if (is_active !== undefined) {
                // Desativação por expiração
                const isActiveBool = is_active === true;
                await sql`
                    UPDATE licenses 
                    SET is_active = ${isActiveBool} 
                    WHERE license_key = ${licenseKey}
                `;
            } else {
                // Associação de dispositivo
                const finalDeviceId = device_id === undefined ? null : device_id;
                const finalActivatedAt = activated_at || new Date().toISOString();
                
                await sql`
                    UPDATE licenses 
                    SET device_id = ${finalDeviceId}, activated_at = ${finalActivatedAt} 
                    WHERE license_key = ${licenseKey}
                `;
            }

            return res.status(200).json({ success: true });
        } catch (e) {
            console.error("Erro no PATCH compatibilidade:", e);
            return res.status(500).json({ error: e.message });
        }
    }

    return res.status(405).json({ error: 'Método não permitido' });
}

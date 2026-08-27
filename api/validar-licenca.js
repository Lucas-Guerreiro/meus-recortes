// API Vercel Serverless Function: api/validar-licenca.js
// Valida se a licença existe, se está ativa, se expirou e se o device_id está correto

import { sql } from '@vercel/postgres';

export default async function handler(req, res) {
    if (req.method !== 'POST') {
        return res.status(405).json({ error: 'Método não permitido' });
    }

    const { license_key, device_id } = req.body;

    if (!license_key) {
        return res.status(400).json({ error: 'Chave da licença é obrigatória' });
    }

    const cleanKey = license_key.trim().toUpperCase();
    const cleanDeviceId = device_id ? device_id.trim() : null;

    try {
        // 1. Busca a licença no Postgres
        const { rows } = await sql`
            SELECT * FROM licenses 
            WHERE license_key = ${cleanKey}
        `;

        if (rows.length === 0) {
            return res.status(404).json({ error: 'Licença inválida ou inexistente!' });
        }

        const license = rows[0];

        // 2. Verifica se a licença está marcada como inativa
        if (license.is_active !== true) {
            return res.status(403).json({ error: 'Esta licença foi desativada pelo administrador!' });
        }

        const now = new Date();

        // 3. Validação de expiração para chaves de teste (3 dias)
        if (cleanKey.startsWith("TEST-") && license.activated_at) {
            const activatedDate = new Date(license.activated_at);
            const diffTime = Math.abs(now - activatedDate);
            const diffDays = diffTime / (1000 * 60 * 60 * 24);

            if (diffDays > 3) {
                // Desativa a licença no banco
                await sql`
                    UPDATE licenses 
                    SET is_active = false 
                    WHERE license_key = ${cleanKey}
                `;
                return res.status(403).json({ error: 'Sua licença de teste de 3 dias expirou!' });
            }
        }

        // 4. Validação de expiração para chaves oficiais (30 dias)
        if (cleanKey.startsWith("MR-") && license.activated_at) {
            const activatedDate = new Date(license.activated_at);
            const diffTime = Math.abs(now - activatedDate);
            const diffDays = diffTime / (1000 * 60 * 60 * 24);

            if (diffDays > 30) {
                // Desativa a licença no banco
                await sql`
                    UPDATE licenses 
                    SET is_active = false 
                    WHERE license_key = ${cleanKey}
                `;
                return res.status(403).json({ error: 'Sua licença mensal de 30 dias expirou!' });
            }
        }

        const dbDeviceId = license.device_id;

        // 5. Se não houver device_id vinculado, vincula o atual
        if (!dbDeviceId || dbDeviceId === "null" || dbDeviceId === "") {
            if (cleanDeviceId) {
                const activatedAtDate = license.activated_at || now.toISOString();
                await sql`
                    UPDATE licenses 
                    SET device_id = ${cleanDeviceId}, activated_at = ${activatedAtDate} 
                    WHERE license_key = ${cleanKey}
                `;
                return res.status(200).json({ success: true, message: 'Dispositivo vinculado e acesso liberado!' });
            } else {
                return res.status(400).json({ error: 'Device ID não fornecido para vinculação' });
            }
        }

        // 6. Se houver device_id, valida se coincide com o atual
        if (dbDeviceId !== cleanDeviceId) {
            return res.status(403).json({ error: 'Esta licença já está em uso em outro aparelho!' });
        }

        return res.status(200).json({ success: true, message: 'Acesso liberado!' });
    } catch (e) {
        console.error(e);
        return res.status(500).json({ error: 'Erro interno no servidor' });
    }
}

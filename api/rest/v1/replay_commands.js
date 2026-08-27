// API Vercel Serverless Function: api/rest/v1/replay_commands.js
// Rota de compatibilidade do Supabase PostgREST para a tabela 'replay_commands'

import { sql } from '@vercel/postgres';

export default async function handler(req, res) {
    let licenseKey = req.query.license_key;
    if (licenseKey && licenseKey.startsWith('eq.')) {
        licenseKey = licenseKey.substring(3);
    }

    if (licenseKey) {
        licenseKey = decodeURIComponent(licenseKey).replace(/['"]/g, '');
    }

    // 1. Método GET - Verificação de novos comandos pela câmera
    if (req.method === 'GET') {
        if (!licenseKey) {
            return res.status(400).json({ error: 'Licenca nao fornecida' });
        }

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

    // 2. Método POST - Envio de comando pelo controle remoto
    if (req.method === 'POST') {
        const { license_key, command } = req.body;

        if (!license_key || !command) {
            return res.status(400).json({ error: 'Licenca e comando sao obrigatorios' });
        }

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

    return res.status(405).json({ error: 'Método não permitido' });
}

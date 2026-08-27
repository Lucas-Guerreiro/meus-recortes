// API Vercel Serverless Function: api/postar-comando.js
// Registra comandos de recorte de video vindos do controle remoto

import { sql } from '@vercel/postgres';

export default async function handler(req, res) {
    if (req.method !== 'POST') {
        return res.status(405).json({ error: 'Método não permitido' });
    }

    const { license_key, command } = req.body;

    if (!license_key || !command) {
        return res.status(400).json({ error: 'Chave de licenca e comando sao obrigatorios' });
    }

    const cleanKey = license_key.trim().toUpperCase();
    const cleanCommand = command.trim();

    try {
        await sql`
            INSERT INTO replay_commands (license_key, command) 
            VALUES (${cleanKey}, ${cleanCommand})
        `;

        return res.status(200).json({ success: true, message: 'Comando registrado com sucesso!' });
    } catch (e) {
        console.error(e);
        return res.status(500).json({ error: 'Erro interno no servidor' });
    }
}

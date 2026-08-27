// API Vercel Serverless Function: api/migrar.js
// Executa a criacao das tabelas no Vercel Postgres

import { sql } from '@vercel/postgres';

export default async function handler(req, res) {
    try {
        console.log("Iniciando migracao do banco de dados...");

        // 1. Cria tabela licenses
        await sql`
            CREATE TABLE IF NOT EXISTS licenses (
                license_key VARCHAR(255) PRIMARY KEY,
                email VARCHAR(255),
                is_active BOOLEAN DEFAULT TRUE,
                device_id VARCHAR(255),
                activated_at TIMESTAMP WITH TIME ZONE
            );
        `;
        console.log("Tabela 'licenses' criada ou verificada.");

        // 2. Cria tabela replay_commands
        await sql`
            CREATE TABLE IF NOT EXISTS replay_commands (
                id SERIAL PRIMARY KEY,
                license_key VARCHAR(255),
                command VARCHAR(255),
                created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
            );
        `;
        console.log("Tabela 'replay_commands' criada ou verificada.");

        return res.status(200).json({ 
            success: true, 
            message: 'Tabelas licenses e replay_commands criadas com sucesso no Vercel Postgres!' 
        });
    } catch (error) {
        console.error("Erro na migracao:", error);
        return res.status(500).json({ error: error.message });
    }
}

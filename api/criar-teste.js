// API Vercel Serverless Function: api/criar-teste.js
// Cria ou renova uma licença de teste gratuita de 3 dias de forma segura usando service role

export default async function handler(req, res) {
    if (req.method !== 'POST') {
        return res.status(405).json({ error: 'Método não permitido' });
    }

    const { email } = req.body;

    if (!email || !email.includes('@')) {
        return res.status(400).json({ error: 'E-mail inválido ou não informado' });
    }

    const SUPABASE_URL = process.env.SUPABASE_URL;
    const SUPABASE_SERVICE_ROLE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY;

    if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
        return res.status(500).json({ error: 'Chaves de servidor do Supabase ausentes' });
    }

    const cleanEmail = email.trim().toLowerCase();

    function generateRandomKey() {
        const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
        let part1 = '';
        let part2 = '';
        let part3 = '';
        for (let i = 0; i < 4; i++) {
            part1 += chars.charAt(Math.floor(Math.random() * chars.length));
            part2 += chars.charAt(Math.floor(Math.random() * chars.length));
            part3 += chars.charAt(Math.floor(Math.random() * chars.length));
        }
        return `TEST-${part1}-${part2}-${part3}`;
    }

    try {
        // 1. Verifica se já existe uma licença para este e-mail
        const checkResponse = await fetch(`${SUPABASE_URL}/rest/v1/licenses?email=eq.${encodeURIComponent(cleanEmail)}&select=*`, {
            method: 'GET',
            headers: {
                'apikey': SUPABASE_SERVICE_ROLE_KEY,
                'Authorization': `Bearer ${SUPABASE_SERVICE_ROLE_KEY}`
            }
        });

        if (checkResponse.ok) {
            const data = await checkResponse.json();
            if (data && data.length > 0) {
                const existingLicense = data[0];
                const licenseKey = existingLicense.license_key;

                // Atualiza (PATCH) a licença existente reativando e redefinindo a data de teste no activated_at
                const patchResponse = await fetch(`${SUPABASE_URL}/rest/v1/licenses?license_key=eq.${licenseKey}`, {
                    method: 'PATCH',
                    headers: {
                        'apikey': SUPABASE_SERVICE_ROLE_KEY,
                        'Authorization': `Bearer ${SUPABASE_SERVICE_ROLE_KEY}`,
                        'Content-Type': 'application/json'
                    },
                    body: JSON.stringify({
                        is_active: true,
                        activated_at: new Date().toISOString(), // Grava a data no activated_at
                        device_id: null // Reseta device_id para permitir autenticação em nova máquina
                    })
                });

                if (patchResponse.ok) {
                    return res.status(200).json({
                        success: true,
                        license_key: licenseKey,
                        message: "Licença de teste renovada com sucesso!"
                    });
                } else {
                    return res.status(500).json({ error: 'Falha ao reativar licença existente' });
                }
            }
        }

        // 2. Não existe licença. Cria uma nova licença TEST-
        const newLicenseKey = generateRandomKey();

        const insertResponse = await fetch(`${SUPABASE_URL}/rest/v1/licenses`, {
            method: 'POST',
            headers: {
                'apikey': SUPABASE_SERVICE_ROLE_KEY,
                'Authorization': `Bearer ${SUPABASE_SERVICE_ROLE_KEY}`,
                'Content-Type': 'application/json',
                'Prefer': 'return=minimal'
            },
            body: JSON.stringify({
                license_key: newLicenseKey,
                is_active: true,
                email: cleanEmail,
                activated_at: new Date().toISOString() // Grava a data inicial no activated_at
            })
        });

        if (!insertResponse.ok) {
            const errorText = await insertResponse.text();
            console.error("Erro Supabase Insert:", errorText);
            return res.status(500).json({ error: 'Erro ao inserir licença no Supabase' });
        }

        return res.status(200).json({
            success: true,
            license_key: newLicenseKey,
            message: "Nova licença de teste gerada com sucesso!"
        });

    } catch (error) {
        console.error(error);
        return res.status(500).json({ error: 'Erro interno ao processar requisição' });
    }
}

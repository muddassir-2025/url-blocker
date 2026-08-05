const express = require('express');
const { exec } = require('child_process');
const path = require('path');
const fs = require('fs');
const app = express();
const port = 3000;

app.use(express.json());

app.get('/', (req, res) => {
    res.sendFile(path.join(__dirname, 'index.html'));
});

app.post('/download', (req, res) => {
    const { url, type } = req.body;
    const myFolder = 'nasheed';     // For MP3s
    const myFolder2 = 'nasheedmp4'; // For Videos

    if (!url) return res.status(400).send('URL is required');

    // 1. Ensure BOTH folders exist
    if (!fs.existsSync(myFolder)) fs.mkdirSync(myFolder);
    if (!fs.existsSync(myFolder2)) fs.mkdirSync(myFolder2);

    // 2. Pick the command and the folder based on the "type"
    let command;
    let targetFolder;

    if (type === 'mp4') {
        targetFolder = myFolder2;
        command = `yt-dlp "${url}" -f "mp4" --no-keep-video -o "${targetFolder}/%(title)s.%(ext)s"`;
    } else {
        targetFolder = myFolder;
        command = `yt-dlp "${url}" -x --audio-format mp3 --no-keep-video -o "${targetFolder}/%(title)s.%(ext)s"`;
    }

    console.log(`Starting ${type} download to /${targetFolder} for: ${url}`);

    // 3. Execute
    exec(command, (error, stdout, stderr) => {
        if (error) {
            console.error(error);
            return res.status(500).json({ success: false, message: error.message });
        }
        res.json({ success: true, message: `${type.toUpperCase()} saved to /${targetFolder}!` });
    });
});

app.listen(port, () => {
    console.log(`Server running at http://localhost:${port}`);
});
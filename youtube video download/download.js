const { exec } = require('child_process');

// 1. SET YOUR URL
const videoUrl = 'https://youtu.be/zT8uMBg7KgA?si=Rm7b0sZZ4EtEWicY'; 

// 2. SET YOUR FOLDER (Example: your Downloads or Music folder)
// Important: Use double backslashes \\ between folders
const myFolder = 'nasheed';

// 3. THE COMMAND
// This tells yt-dlp: Save to [Folder]/[Title].mp3
//const command = `yt-dlp -x --audio-format mp3 -o "${myFolder}/%(title)s.%(ext)s" ${videoUrl}`;
const command = `yt-dlp -x --audio-format mp3 -o "${myFolder}/%(title)s.%(ext)s" --postprocessor-args "ffmpeg:-y" ${videoUrl}`;


console.log(`Downloading to: ${myFolder}`);

exec(command, (error, stdout, stderr) => {
    if (error) {
        console.error(`Error: ${error.message}`);
        return;
    }
    console.log("Success! File saved to your folder.");
    console.log(stdout);
});
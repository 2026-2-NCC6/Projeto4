const express = require('express');
const mysql = require('mysql2');
const bodyParser = require('body-parser');
const path = require('path');
const cors = require('cors')
const multer = require('multer');
const dotenv = require('dotenv');
const fs = require('fs');

dotenv.config();


const storage = multer.diskStorage({
    destination: function (req, file, cb) {
        cb(null, path.join(__dirname, 'uploads')); 
    },
    filename: function (req, file, cb) {
        cb(null, file.originalname);
    }
});

const upload = multer({ storage: storage });

const app = express();
const port = process.env.PORT || 5000;

app.use('/uploads', express.static(path.join(__dirname, 'uploads')));

// Middleware
app.use(cors());
app.use(bodyParser.json({ limit: '50mb' }));
app.use(bodyParser.urlencoded({ limit: '50mb', extended: true }));

// Conexão MySQL
const db = mysql.createConnection({
  host: process.env.DB_HOST,
  user: process.env.DB_USER,
  password: process.env.DB_PASSWORD,
  database: process.env.DB_NAME,
  ssl: {
    rejectUnauthorized: false
  }
});

db.connect((err) => {
    if (err) {
      console.error('Erro ao conectar a database', err);
      return;
    }
    console.log('Conectado a Database MySQL');
  });
  
  app.use(express.json()); // Json para POST

app.get("/buscarUsuarios", function(req,res){
  query = "Select * from usuario"

  db.query(query,(err, result)=>{
    if(err){
      console.error("Erro ao buscar usuarios", err)
      return res.status(500).json({error: "Erro ao buscar usuários"})
    }
    res.status(200).json(result)
  })
})

app.listen(port, () => {
  console.log(`Server running on ${port}`);
})
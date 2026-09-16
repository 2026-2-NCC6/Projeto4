CREATE DATABASE smarttennis;

use smarttennis;

create table usuario(
id int primary key auto_increment,
nome varchar(30),
email varchar(50),
senha varchar(50)
);

create table exercicio(
id int primary key auto_increment,
titulo varchar(30),
autor varchar(30),
dificuldade int,
descricao varchar(100),
duracao time,
dados varchar(500),
objetivo int
);

create table usuarioexercicio(
id int primary key auto_increment,
acertos float,
erros float,
nota float,
tempo time,
dados varchar(500),
idUsuario int,
foreign key (idUsuario) references usuario(id),
idExercicio int,
foreign key (idExercicio) references exercicio(id)
);

insert into usuario(nome, email, senha) values ("Teste", "teste@gmail.com", "testando1234");

SELECT * from usuario;
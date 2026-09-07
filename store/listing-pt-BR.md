# Detailed description — Portuguese (Brazil)

> Paste everything below the line into the Web Store's "Detailed description" field for the Portuguese
> (Brazil) listing. The name and the short description come from `_locales/pt_BR/messages.json` in the
> uploaded ZIP, not from here.
>
> **The field is plain text.** It renders no Markdown and no HTML: `**bold**` would appear with its
> asterisks, and so the text below has none. What it does keep is line breaks and blank lines, which is
> what separates the headings from the paragraphs under them. Paste it as it is — do not re-add emphasis.

---

Sua página de nova aba, com suas abas nela.

O stramus transforma a página que você abre vinte vezes por dia no lugar onde ficam as páginas que você
realmente usa. Salve as abas abertas em uma coleção, dê um nome a ela e feche-as sem perder nada. Tudo
fica guardado na sua própria máquina, e funciona assim sem conta nenhuma — entre com uma conta só se
quiser as mesmas coleções no seu outro navegador.

Salve suas abas abertas.
O painel de abas lista cada aba em cada janela. Arraste uma para uma coleção, ou salve a janela inteira
de uma vez. Uma aba salva pode ser fechada na hora — é exatamente para isso que serve.

Salve uma página sem abrir o stramus.
Um atalho de teclado, um clique com o botão direito ou o botão da barra de ferramentas salva a página em
que você está — ou qualquer link nela — a partir de qualquer aba. Uma notificação diz "salvo" na hora, e
isso vira um cartão na próxima vez que você abrir o stramus.

Organize do seu jeito.
As seções na barra lateral guardam coleções; as coleções guardam cartões; os cartões podem ser agrupados
sob títulos criados por você. Tudo é arrastar e soltar, e o que for desfeito por acidente pode ser
refeito.

Cartões não são só links.
Um cartão pode ser um link, uma nota escrita em Markdown, ou um arquivo — uma imagem, um PDF, o que você
soltar nele — guardado na coleção junto com os links aos quais pertence.

Encontre com uma tecla.
A busca pesquisa ao mesmo tempo seus cartões, suas abas abertas e seu histórico de navegação, e classifica
os resultados pelo que você realmente costuma abrir. Uma busca vazia já mostra seus sites mais visitados.
Se o que você digitou é uma pesquisa e não um endereço, ela vai para o seu mecanismo de busca padrão.

Pergunte ao modelo do próprio navegador.
Onde o Chrome tem um modelo no próprio dispositivo, a busca oferece perguntar a ele — numa janela sobre
sua coleção, na sua máquina, sem enviar nada para lugar nenhum. Prefere um assistente web? Escolha
ChatGPT, Gemini ou Claude nas configurações, e sua pergunta abre num chat com eles.

Guarde uma parte só para você.
Uma seção pode ser bloqueada com um PIN, para que o que está atrás dela não apareça na tela quando você
a compartilha.

Do seu jeito, também na aparência.
Cinco temas, cada um com uma metade clara e uma escura, uma cor de destaque sua e um fundo atrás do
aplicativo — um gradiente ou uma imagem sua. Uma coleção pode receber uma marca: um símbolo colorido ou
um emoji. Duas densidades de cartão e três raios de canto, se isso importa para você.

E o resto.
Onze idiomas de interface, do inglês ao turco. Importação dos favoritos, de um arquivo CSV, de uma
exportação do OneTab ou de uma do Toby; exportação de volta para favoritos ou CSV, ou o banco inteiro em
um único arquivo de backup. Quadros dos vídeos salvos, desligados até você ligá-los. Ordenação de abas.
Um cache de favicons, para que seus links mantenham os ícones mesmo offline.

Privado por construção.
Sem análise, sem telemetria, sem rastreamento, sem publicidade — com ou sem conta. Sem conta, nenhum dos
seus dados sai da sua máquina: suas coleções não têm nenhum servidor para onde ir. Com uma conta, elas são
sincronizadas para que seu outro navegador as tenha também — e nada além disso: suas estatísticas de
navegação ficam nesta máquina a não ser que você mesmo as ative, e seu histórico de navegação nunca é
enviado. O stramus lê suas abas e seu histórico porque é isso que um gerenciador de abas faz, e as lê no
momento, sem guardar nenhuma cópia.

Os ícones dos sites vêm do próprio repositório de ícones do navegador, então um site que você já visitou
é desenhado sem perguntar a ninguém. Para um site que você não visitou, o ícone é buscado pelo nosso
servidor em seu nome — de forma anônima, sem conta associada e sem nada registrado — em vez de entregar
seus endereços salvos, um a um, a um serviço público de ícones.

Um PIN numa seção a tira da tela; não é criptografia, e não a esconde do servidor. A política de
privacidade diz isso claramente, e diz também o que mais uma conta significa.

Novidades da 1.5.2.
A busca agora perdoa um erro de digitação e perdoa um layout de teclado esquecido: uma consulta digitada
no layout errado é refeita no outro, de modo que «ыекфьгы» encontra o Stramus. Isso vale para seus links,
suas abas abertas e o histórico; o histórico quem procura é o próprio navegador, letra por letra, então
uma busca sem resultado ali recorre às páginas que você viu há pouco. As configurações ganharam uma busca
própria: ela esmaece o que não combina e rola até o que combina, em vez de esconder o resto. A seção de
salvamentos rápidos não é mais criada uma segunda vez num navegador que já a tinha. A sincronização envia
uma conta grande em páginas do mesmo jeito que a traz, e um grupo excluído na outra máquina não deixa mais
seus links pendurados nele.
A camada de índices sob o banco também subiu de versão: uma consulta sobre um índice composto podia
devolver uma linha a mais, e era assim que uma duplicata sobrevivia a uma importação ou a uma junção.

Política de privacidade: https://stramus.space/privacy.html
Código-fonte: https://github.com/olluorg/stramus

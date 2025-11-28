from fastapi import FastAPI
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer
from dotenv import load_dotenv
import os

load_dotenv()

app = FastAPI()
model = SentenceTransformer(os.getenv('EMBEDDING_MODEL_NAME', 'jhgan/ko-sroberta-sts'))

class TextRequest(BaseModel):
    text: str

class TextsRequest(BaseModel):
    texts: list[str]

@app.post("/embed")
async def embed_text(request: TextRequest):
    embedding = model.encode(request.text).tolist()
    return {"embedding": embedding}

@app.post("/embed/batch")
async def embed_texts(request: TextsRequest):
    embeddings = model.encode(request.texts).tolist()
    return {"embeddings": embeddings}
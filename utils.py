import os
import pika
from dotenv import load_dotenv

load_dotenv()

def get_connection():
    host = os.getenv('RABBITMQ_HOST')
    password = os.getenv('RABBITMQ_PASSWORD')
    vhost = os.getenv('RABBITMQ_VHOST')
    port = os.getenv('RABBITMQ_PORT')
    user = os.getenv('RABBITMQ_USER')

    credentials = pika.PlainCredentials(user, password)
    parameters = pika.ConnectionParameters(host, port, vhost, credentials)

    return pika.BlockingConnection(parameters)